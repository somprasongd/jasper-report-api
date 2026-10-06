package com.github.somprasongd.jasperreport.api.source;

import java.nio.file.Path;

/**
 * A report folder ("bundle": main JRXML, sub-report JRXMLs, images) materialised on the local file system.
 * {@code version} changes whenever any file of the bundle changes.
 *
 * @param bundleId stable identity of the bundle independent of version, e.g. {@code s3://reports/opd/cert/}
 * @param dir      local directory holding the bundle files
 * @param mainFile file name of the main JRXML inside {@code dir}
 * @param version  content/version fingerprint of the bundle
 */
public record ResolvedBundle(String bundleId, Path dir, String mainFile, String version) {

    public Path mainPath() {
        return dir.resolve(mainFile);
    }

    public String cacheKey() {
        return bundleId + "@" + version;
    }
}
