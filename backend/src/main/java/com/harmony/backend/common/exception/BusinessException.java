package com.harmony.backend.common.exception;

public class BusinessException extends RuntimeException {

    private Integer code;

    public BusinessException(String message) {
        super(message);
    }

    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
    }

    public BusinessException(String message, Throwable cause) {
        super(message, cause);
        this.code = 400;
    }

    public Integer getCode() {
        return code;
    }
}