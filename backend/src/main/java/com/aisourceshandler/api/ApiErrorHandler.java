package com.aisourceshandler.api;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class ApiErrorHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiErrorHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, Object>> handleApi(ApiException exception) {
        return ResponseEntity.status(exception.status()).body(Map.of(
                "requestId", requestId(),
                "errorCode", exception.errorCode(),
                "message", exception.getMessage(),
                "retryable", exception.retryable(),
                "details", Map.of()
        ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException exception) {
        return ResponseEntity.badRequest().body(Map.of(
                "requestId", requestId(),
                "errorCode", "VALIDATION_FAILED",
                "message", exception.getBindingResult().getAllErrors().getFirst().getDefaultMessage(),
                "retryable", false,
                "details", Map.of()
        ));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> handleUnknown(Exception exception, HttpServletRequest request) {
        log.error("Unhandled request failure requestId={} method={} path={}",
                requestId(), request.getMethod(), request.getRequestURI(), exception);
        return ResponseEntity.internalServerError().body(Map.of(
                "requestId", requestId(),
                "errorCode", "INTERNAL_ERROR",
                "message", "请求处理失败，请根据 requestId 查看日志。",
                "retryable", true,
                "details", Map.of("path", request.getRequestURI())
        ));
    }

    private String requestId() {
        return MDC.get("requestId") == null ? "unknown" : MDC.get("requestId");
    }
}
