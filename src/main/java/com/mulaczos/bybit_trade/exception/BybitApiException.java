package com.mulaczos.bybit_trade.exception;

public class BybitApiException extends RuntimeException {
    public BybitApiException(String message) {
        super(message);
    }

    public BybitApiException(String message, Throwable cause) {
        super(message, cause);
    }
} 