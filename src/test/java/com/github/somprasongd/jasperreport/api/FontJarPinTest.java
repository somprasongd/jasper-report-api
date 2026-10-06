package com.github.somprasongd.jasperreport.api;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The font jar (jasper-report-api-thai-fonts:2.0.0) is pinned by SHA-256: the distribution approval of the
 * TH Sarabun New font is bound to it (see docs/design, section 9). Changing the jar needs a new approval.
 */
class FontJarPinTest {

    @Test
    void fontJarMatchesThePinnedSha256() throws Exception {
        String[] pin = Files.readString(Path.of("libs/font-jar.sha256")).trim().split("\\s+", 2);
        byte[] jar = Files.readAllBytes(Path.of(pin[1]));
        String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(jar));
        assertThat(actual).isEqualTo(pin[0]);
    }
}
