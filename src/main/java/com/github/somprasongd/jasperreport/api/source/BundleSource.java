package com.github.somprasongd.jasperreport.api.source;

/** A place JRXML bundles can be loaded from (local folder, S3-compatible storage, allow-listed HTTP host). */
interface BundleSource {

    ResolvedBundle resolve(String url);
}
