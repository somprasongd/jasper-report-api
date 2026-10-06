package com.github.somprasongd.jasperreport.api.source;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class Hashing {

    private Hashing() {
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String hex(MessageDigest digest, int length) {
        return HexFormat.of().formatHex(digest.digest()).substring(0, length);
    }

    static String hex(String value, int length) {
        MessageDigest digest = sha256();
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        return hex(digest, length);
    }

    static String hex(byte[] value, int length) {
        MessageDigest digest = sha256();
        digest.update(value);
        return hex(digest, length);
    }
}
