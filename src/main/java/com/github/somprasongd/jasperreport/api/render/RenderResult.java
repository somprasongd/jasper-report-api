package com.github.somprasongd.jasperreport.api.render;

public record RenderResult(byte[] content, String contentType, String fileName, String version, String locale, boolean inline) {
}
