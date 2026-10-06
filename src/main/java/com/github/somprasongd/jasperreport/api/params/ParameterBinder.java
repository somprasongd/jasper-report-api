package com.github.somprasongd.jasperreport.api.params;

import com.github.somprasongd.jasperreport.api.config.ReportProperties;
import com.github.somprasongd.jasperreport.api.web.ApiException;
import net.sf.jasperreports.engine.JRParameter;
import net.sf.jasperreports.engine.JasperReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Converts request parameters to the Java types the report declares. Only parameters declared in the JRXML
 * are accepted; system/infrastructure parameters ({@code SUBREPORT_DIR}, {@code IMAGE_DIR}, ...) are set by the
 * API and silently dropped from the request.
 */
@Component
public class ParameterBinder {

    /** Parameters the API owns; a client value for any of them is ignored. */
    public static final Set<String> RESERVED = Set.of(
            "SUBREPORTS", "SUBREPORT_DIR", "IMAGE_DIR", "REPORT_ASSETS_DIR", "REPORT_LANGUAGE", "REPORT_CONNECTION",
            "REPORT_VIRTUALIZER", "REPORT_LOCALE", "REPORT_TIME_ZONE", "REPORT_PARAMETERS_MAP",
            "REPORT_DATA_SOURCE", "REPORT_CONTEXT", "REPORT_CLASS_LOADER", "REPORT_SCRIPTLET",
            "REPORT_RESOURCE_BUNDLE", "REPORT_MAX_COUNT", "REPORT_FORMAT_FACTORY", "REPORT_URL_HANDLER_FACTORY",
            "REPORT_FILE_RESOLVER", "IS_IGNORE_PAGINATION", "JASPER_REPORT", "REPORT_TEMPLATES");

    private static final Logger log = LoggerFactory.getLogger(ParameterBinder.class);

    private final boolean strict;
    private final ZoneId zone;

    public ParameterBinder(ReportProperties properties) {
        this.strict = properties.parameters().strict();
        this.zone = ZoneId.of(properties.timezone());
    }

    public Map<String, Object> bind(JasperReport report, List<ParamInput> inputs) {
        Map<String, JRParameter> declared = new HashMap<>();
        for (JRParameter p : report.getParameters()) {
            declared.put(p.getName(), p);
        }
        Map<String, Object> result = new HashMap<>();
        Set<String> dropped = new HashSet<>();
        for (ParamInput input : inputs == null ? List.<ParamInput>of() : inputs) {
            String name = input.name();
            if (RESERVED.contains(name) || RESERVED.contains(name.toUpperCase(Locale.ROOT))) {
                dropped.add(name);
                continue;
            }
            JRParameter parameter = declared.get(name);
            if (parameter == null || parameter.isSystemDefined()) {
                if (strict) {
                    throw ApiException.badRequest("PARAMETER_INVALID", "parameter '" + name + "' is not declared by the report");
                }
                dropped.add(name);
                continue;
            }
            result.put(name, convert(name, input.value(), parameter.getValueClass(), parameter.getNestedType(), input.type()));
        }
        if (!dropped.isEmpty()) {
            log.warn("Ignored request parameters not accepted by the report: {}", dropped);
        }
        return result;
    }

    Object convert(String name, Object raw, Class<?> target, Class<?> nested, String hint) {
        if (raw == null) {
            return null;
        }
        try {
            return convertValue(raw, target, nested, hint);
        } catch (ApiException e) {
            throw e;
        } catch (RuntimeException e) {
            throw ApiException.badRequest("PARAMETER_INVALID",
                    "parameter '" + name + "' cannot be converted to " + describe(target, hint) + ": " + e.getMessage());
        }
    }

    private static String describe(Class<?> target, String hint) {
        return target == Object.class && hint != null ? hint : target.getSimpleName();
    }

    private Object convertValue(Object raw, Class<?> target, Class<?> nested, String hint) {
        if (target == Object.class) {
            return hint == null ? raw : convertByHint(raw, hint);
        }
        if (Collection.class.isAssignableFrom(target)) {
            return toCollection(raw, target, nested, hint);
        }
        String text = String.valueOf(raw).trim();
        if (target == String.class) {
            return raw instanceof Collection<?> || raw instanceof Map<?, ?> ? raw.toString() : String.valueOf(raw);
        }
        if (target == Integer.class) {
            return raw instanceof Number n && isWhole(n) ? Integer.valueOf(Math.toIntExact(n.longValue())) : Integer.valueOf(text);
        }
        if (target == Long.class) {
            return raw instanceof Number n && isWhole(n) ? Long.valueOf(n.longValue()) : Long.valueOf(text);
        }
        if (target == Short.class) {
            return Short.valueOf(text);
        }
        if (target == Byte.class) {
            return Byte.valueOf(text);
        }
        if (target == Double.class) {
            return raw instanceof Number n ? Double.valueOf(n.doubleValue()) : Double.valueOf(text);
        }
        if (target == Float.class) {
            return raw instanceof Number n ? Float.valueOf(n.floatValue()) : Float.valueOf(text);
        }
        if (target == BigDecimal.class) {
            return new BigDecimal(text);
        }
        if (target == BigInteger.class) {
            return new BigInteger(text);
        }
        if (target == Number.class) {
            return raw instanceof Number ? raw : new BigDecimal(text);
        }
        if (target == Boolean.class) {
            if (raw instanceof Boolean b) {
                return b;
            }
            if (text.equalsIgnoreCase("true") || text.equals("1")) {
                return Boolean.TRUE;
            }
            if (text.equalsIgnoreCase("false") || text.equals("0")) {
                return Boolean.FALSE;
            }
            throw new IllegalArgumentException("expected true/false");
        }
        if (target == java.sql.Date.class) {
            return toSqlDate(text);
        }
        if (target == java.util.Date.class) {
            return text.length() <= 10 ? toSqlDate(text) : toTimestamp(text);
        }
        if (target == Time.class) {
            return toTime(text);
        }
        if (target == Timestamp.class) {
            return toTimestamp(text);
        }
        if (target.isInstance(raw)) {
            return raw;
        }
        throw new IllegalArgumentException("unsupported parameter class " + target.getName());
    }

    private Object convertByHint(Object raw, String hint) {
        String text = String.valueOf(raw).trim();
        return switch (hint.toLowerCase(Locale.ROOT)) {
            case "string" -> String.valueOf(raw);
            case "integer" -> Integer.valueOf(text);
            case "number" -> new BigDecimal(text).doubleValue();
            case "date" -> toSqlDate(text);
            case "time" -> toTime(text);
            case "timestamp" -> toTimestamp(text);
            case "bool", "boolean" -> Boolean.parseBoolean(text);
            case "array_str", "array_int" -> toCollection(raw, List.class, null, hint);
            default -> throw new IllegalArgumentException("unknown type '" + hint + "'");
        };
    }

    private Object toCollection(Object raw, Class<?> target, Class<?> nested, String hint) {
        List<?> items;
        if (raw instanceof Collection<?> c) {
            items = new ArrayList<>(c);
        } else {
            String text = String.valueOf(raw).trim();
            items = text.isEmpty() ? List.of() : List.of(text.split("\\s*,\\s*"));
        }
        Class<?> element = nested != null ? nested : hint == null ? null
                : hint.equalsIgnoreCase("array_int") ? Integer.class
                : hint.equalsIgnoreCase("array_str") ? String.class : null;
        List<Object> converted = new ArrayList<>(items.size());
        for (Object item : items) {
            converted.add(element == null || item == null ? item : convertValue(item, element, null, null));
        }
        return Set.class.isAssignableFrom(target) ? new java.util.LinkedHashSet<>(converted) : converted;
    }

    private static boolean isWhole(Number n) {
        return n instanceof Integer || n instanceof Long || n instanceof Short || n instanceof Byte
                || n instanceof BigInteger || (n.doubleValue() == Math.rint(n.doubleValue()) && !Double.isInfinite(n.doubleValue()));
    }

    private java.sql.Date toSqlDate(String text) {
        if (text.length() > 10) {
            return java.sql.Date.valueOf(toTimestamp(text).toLocalDateTime().toLocalDate());
        }
        return java.sql.Date.valueOf(LocalDate.parse(text));
    }

    /** {@code HH:mm[:ss]} in the report time zone, or with an offset ({@code 10:30:00Z}, {@code 10:30:00+07:00}). */
    private Time toTime(String text) {
        try {
            return Time.valueOf(LocalTime.parse(text));
        } catch (DateTimeException withoutOffset) {
            OffsetTime offset = OffsetTime.parse(text);
            ZoneId z = zone;
            return Time.valueOf(offset.withOffsetSameInstant(z.getRules().getOffset(java.time.Instant.now())).toLocalTime());
        }
    }

    /** ISO-8601; an offset or {@code Z} is honoured, otherwise the value is in the report time zone. */
    private Timestamp toTimestamp(String text) {
        String normalized = text.indexOf(' ') == 10 ? text.replace(' ', 'T') : text;
        try {
            return Timestamp.from(OffsetDateTime.parse(normalized).toInstant());
        } catch (DateTimeException withoutOffset) {
            if (normalized.length() == 10) {
                return Timestamp.valueOf(LocalDate.parse(normalized).atStartOfDay());
            }
            return Timestamp.from(LocalDateTime.parse(normalized).atZone(zone).toInstant());
        }
    }
}
