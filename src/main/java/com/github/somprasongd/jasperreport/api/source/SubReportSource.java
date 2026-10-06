package com.github.somprasongd.jasperreport.api.source;

/**
 * A sub-report listed in the request ({@code subReports[]}). Only http(s) sources need it: a folder or an S3
 * prefix already contains its sub-reports, a single URL does not.
 *
 * @param baseName file name without extension the report is stored under in the bundle ({@code sub_info})
 * @param url      where to download it from
 */
public record SubReportSource(String baseName, String url) {
}
