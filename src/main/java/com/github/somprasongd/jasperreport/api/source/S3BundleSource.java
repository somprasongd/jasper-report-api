package com.github.somprasongd.jasperreport.api.source;

import com.github.somprasongd.jasperreport.api.config.ReportProperties;
import com.github.somprasongd.jasperreport.api.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Syncs the "folder" (key prefix) that contains the main JRXML from an S3-compatible store (rustfs, MinIO, AWS)
 * into the work directory, keyed by the objects' ETags. Only buckets from the allow-list are readable.
 */
class S3BundleSource implements BundleSource {

    private static final Logger log = LoggerFactory.getLogger(S3BundleSource.class);

    private final ReportProperties.S3 settings;
    private final Path workDir;
    private final int keepVersions;
    private volatile S3Client client;

    S3BundleSource(ReportProperties.S3 settings, Path workDir, int keepVersions) {
        this.settings = settings;
        this.workDir = workDir.resolve("s3");
        this.keepVersions = Math.max(1, keepVersions);
    }

    S3Client client() {
        S3Client local = client;
        if (local == null) {
            synchronized (this) {
                local = client;
                if (local == null) {
                    local = S3Client.builder()
                            .endpointOverride(URI.create(settings.endpoint()))
                            .region(Region.of(settings.region()))
                            .credentialsProvider(StaticCredentialsProvider.create(
                                    AwsBasicCredentials.create(settings.accessKey(), settings.secretKey())))
                            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(settings.pathStyleAccess()).build())
                            .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                            .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                            .httpClientBuilder(UrlConnectionHttpClient.builder())
                            .build();
                    client = local;
                }
            }
        }
        return local;
    }

    /** Cheap reachability probe used by the readiness check. */
    void probe() {
        client().listObjectsV2(ListObjectsV2Request.builder().bucket(settings.allowedBuckets().get(0)).maxKeys(1).build());
    }

    @Override
    public ResolvedBundle resolve(String url) {
        String rest = url.substring("s3://".length());
        int slash = rest.indexOf('/');
        if (slash <= 0) {
            throw ApiException.badRequest("SOURCE_NOT_ALLOWED", "expected s3://<bucket>/<folder>/<report>.jrxml");
        }
        String bucket = rest.substring(0, slash);
        String key = rest.substring(slash + 1);
        if (!settings.configured() || !settings.allowedBuckets().contains(bucket)) {
            throw ApiException.badRequest("SOURCE_NOT_ALLOWED", "bucket '" + bucket + "' is not in report.sources.s3.allowed-buckets");
        }
        if (key.contains("\\") || key.startsWith("/") || key.indexOf('\0') >= 0
                || List.of(key.split("/", -1)).contains("..") || List.of(key.split("/", -1)).contains(".")) {
            throw ApiException.badRequest("SOURCE_NOT_ALLOWED", "invalid object key");
        }
        if (!key.toLowerCase(Locale.ROOT).endsWith(".jrxml")) {
            throw ApiException.badRequest("SOURCE_NOT_ALLOWED", "report must be a .jrxml object");
        }
        int lastSlash = key.lastIndexOf('/');
        if (lastSlash < 0) {
            throw ApiException.badRequest("SOURCE_NOT_ALLOWED",
                    "the report must be inside a folder (key prefix); the whole folder is synced as its bundle");
        }
        String prefix = key.substring(0, lastSlash + 1);
        String mainFile = key.substring(lastSlash + 1);

        try {
            List<S3Object> objects = list(bucket, prefix);
            if (objects.stream().noneMatch(o -> o.key().equals(key))) {
                throw new ApiException(HttpStatus.NOT_FOUND, "REPORT_NOT_FOUND", "report not found: s3://" + bucket + "/" + key);
            }
            MessageDigest digest = Hashing.sha256();
            for (S3Object o : objects) {
                digest.update((o.key() + "|" + o.eTag() + "|" + o.size() + "\n").getBytes(StandardCharsets.UTF_8));
            }
            String version = Hashing.hex(digest, 16);
            Path bundleRoot = workDir.resolve(Hashing.hex(bucket + "/" + prefix, 12));
            Path target = bundleRoot.resolve(version);
            if (!Files.isDirectory(target)) {
                download(bucket, prefix, objects, bundleRoot, target);
                WorkDirs.prune(bundleRoot, target, keepVersions);
            }
            return new ResolvedBundle("s3://" + bucket + "/" + prefix, target, mainFile, version);
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                throw new ApiException(HttpStatus.NOT_FOUND, "REPORT_NOT_FOUND", "bucket or report not found: s3://" + bucket + "/" + key, e);
            }
            throw new ApiException(HttpStatus.BAD_GATEWAY, "STORAGE_ERROR", "S3 error: " + e.awsErrorDetails().errorMessage(), e);
        } catch (software.amazon.awssdk.core.exception.SdkException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "STORAGE_ERROR", "cannot reach S3 storage: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "cannot store bundle: " + e.getMessage(), e);
        }
    }

    private List<S3Object> list(String bucket, String prefix) {
        List<S3Object> objects = new ArrayList<>();
        long total = 0;
        String token = null;
        do {
            ListObjectsV2Response page = client().listObjectsV2(ListObjectsV2Request.builder()
                    .bucket(bucket).prefix(prefix).continuationToken(token).build());
            for (S3Object o : page.contents()) {
                if (o.key().endsWith("/")) {
                    continue;
                }
                String relative = o.key().substring(prefix.length());
                if (relative.startsWith(".") || relative.contains("/.")) {
                    continue;
                }
                objects.add(o);
                total += o.size();
                if (objects.size() > settings.maxObjects()) {
                    throw ApiException.badRequest("SOURCE_NOT_ALLOWED",
                            "folder s3://" + bucket + "/" + prefix + " has more than " + settings.maxObjects() + " objects");
                }
                if (total > settings.maxBundleBytes().toBytes()) {
                    throw ApiException.badRequest("SOURCE_NOT_ALLOWED",
                            "folder s3://" + bucket + "/" + prefix + " is larger than " + settings.maxBundleBytes());
                }
            }
            token = page.isTruncated() ? page.nextContinuationToken() : null;
        } while (token != null);
        objects.sort(Comparator.comparing(S3Object::key));
        return objects;
    }

    private void download(String bucket, String prefix, List<S3Object> objects, Path bundleRoot, Path target) throws IOException {
        Files.createDirectories(bundleRoot);
        Path tmp = bundleRoot.resolve(".tmp-" + UUID.randomUUID());
        try {
            Files.createDirectories(tmp);
            for (S3Object o : objects) {
                Path file = tmp.resolve(o.key().substring(prefix.length())).normalize();
                if (!file.startsWith(tmp)) {
                    throw ApiException.badRequest("SOURCE_NOT_ALLOWED", "invalid object key " + o.key());
                }
                Files.createDirectories(file.getParent());
                client().getObject(GetObjectRequest.builder().bucket(bucket).key(o.key()).build(),
                        ResponseTransformer.toFile(file));
            }
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
                log.info("Synced s3://{}/{} ({} objects) -> {}", bucket, prefix, objects.size(), target);
            } catch (java.nio.file.FileAlreadyExistsException | java.nio.file.DirectoryNotEmptyException e) {
                WorkDirs.deleteRecursively(tmp); // another request synced the same version first
            }
        } catch (RuntimeException | IOException e) {
            WorkDirs.deleteRecursively(tmp);
            throw e;
        }
    }
}
