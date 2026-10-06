package com.github.somprasongd.jasperreport.api.params;

import jakarta.validation.constraints.NotBlank;

/**
 * One request parameter. {@code type} is optional: the declared JRXML parameter class decides; the hint only
 * matters for loosely typed parameters ({@code Object}, {@code Collection}). Values may be JSON strings,
 * numbers, booleans, arrays or null.
 */
public record ParamInput(@NotBlank String name, String type, Object value) {
}
