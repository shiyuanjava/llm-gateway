package com.llm.gateway.admin.web;

import org.springframework.http.HttpStatus;

/** A typed business failure for the management API. */
public class AdminApiException extends RuntimeException {

    private final HttpStatus status;

    private AdminApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    public static AdminApiException badRequest(String message) {
        return new AdminApiException(HttpStatus.BAD_REQUEST, message);
    }

    public static AdminApiException notFound(String message) {
        return new AdminApiException(HttpStatus.NOT_FOUND, message);
    }

    public static AdminApiException conflict(String message) {
        return new AdminApiException(HttpStatus.CONFLICT, message);
    }

    public static AdminApiException unavailable(String message) {
        return new AdminApiException(HttpStatus.SERVICE_UNAVAILABLE, message);
    }
}
