package com.github.somprasongd.jasperreport.api.security;

import com.github.somprasongd.jasperreport.api.config.ReportProperties;
import com.github.somprasongd.jasperreport.api.config.ReportProperties.ApiKeyEntry;
import com.github.somprasongd.jasperreport.api.config.ReportProperties.ApiKeyMode;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Validates API keys by SHA-256 against the hashes in configuration; the plain key is never stored.
 */
@Service
public class ApiKeyService {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyService.class);
    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-fA-F]{64}");

    private final ReportProperties.ApiKey settings;
    private final List<ApiKeyEntry> entries;

    public ApiKeyService(ReportProperties properties) {
        this.settings = properties.security().apiKey();
        this.entries = properties.security().apiKeys();
    }

    @PostConstruct
    void validateConfiguration() {
        for (ApiKeyEntry entry : entries) {
            if (entry.clientId() == null || entry.clientId().isBlank()) {
                throw new IllegalStateException("report.security.api-keys[].client-id must not be blank");
            }
            if (entry.sha256() == null || !SHA256_HEX.matcher(entry.sha256()).matches()) {
                throw new IllegalStateException("report.security.api-keys[" + entry.clientId()
                        + "].sha256 must be a 64-character hex SHA-256 of the key");
            }
        }
        if (settings.mode() == ApiKeyMode.REQUIRED && entries.stream().noneMatch(ApiKeyEntry::enabled)) {
            throw new IllegalStateException("report.security.api-key.mode=required but no enabled key is configured "
                    + "(generate one with `make api-key CLIENT=<id>`), or set the mode to optional/disabled");
        }
        if (settings.mode() != ApiKeyMode.REQUIRED) {
            log.warn("API key mode is {}: requests without a key are accepted. Restrict the network accordingly.", settings.mode());
        }
    }

    public ApiKeyMode mode() {
        return settings.mode();
    }

    public String headerName() {
        return settings.header();
    }

    /** @return the client id the key belongs to, or empty if the key is unknown, disabled or expired. */
    public Optional<String> authenticate(String presentedKey) {
        byte[] presented = sha256(presentedKey);
        OffsetDateTime now = OffsetDateTime.now();
        String match = null;
        for (ApiKeyEntry entry : entries) {
            // compare against every entry so the time taken does not reveal which one matched
            boolean same = MessageDigest.isEqual(presented, HexFormat.of().parseHex(entry.sha256().toLowerCase()));
            boolean usable = entry.enabled() && (entry.expiresAt() == null || entry.expiresAt().isAfter(now));
            if (same && usable) {
                match = entry.clientId();
            }
        }
        return Optional.ofNullable(match);
    }

    public static String sha256Hex(String value) {
        return HexFormat.of().formatHex(sha256(value));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
