package com.github.somprasongd.jasperreport.api.render;

/**
 * Where a render gets its rows from: a named JDBC datasource, the request's JSON {@code data}, or nothing at all
 * (a report that only prints its parameters and layout).
 */
public record DataPlan(Kind kind, String datasource) {

    public enum Kind {
        DATABASE, JSON, NONE
    }

    /** Reserved datasource name meaning "no database"; cannot be configured as a real datasource. */
    public static final String NONE_NAME = "none";

    public static DataPlan database(String name) {
        return new DataPlan(Kind.DATABASE, name);
    }

    public static DataPlan json() {
        return new DataPlan(Kind.JSON, "json");
    }

    public static DataPlan none() {
        return new DataPlan(Kind.NONE, NONE_NAME);
    }

    public static boolean isNone(String name) {
        return NONE_NAME.equalsIgnoreCase(name);
    }
}
