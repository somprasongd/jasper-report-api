package com.github.somprasongd.jasperreport.api.web;

import org.springframework.http.HttpStatus;

/** A failure that maps to an RFC 9457 problem response with a stable machine-readable {@code code}. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private Integer retryAfterSeconds;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public ApiException(HttpStatus status, String code, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
    }

    public ApiException retryAfter(int seconds) {
        this.retryAfterSeconds = seconds;
        return this;
    }

    public Integer retryAfterSeconds() {
        return retryAfterSeconds;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }
}
