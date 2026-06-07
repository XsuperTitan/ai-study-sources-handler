package com.aisourceshandler.api;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String errorCode;
    private final boolean retryable;

    public ApiException(HttpStatus status, String errorCode, String message, boolean retryable) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    public ApiException(HttpStatus status, String errorCode, String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    public HttpStatus status() { return status; }
    public String errorCode() { return errorCode; }
    public boolean retryable() { return retryable; }
}
