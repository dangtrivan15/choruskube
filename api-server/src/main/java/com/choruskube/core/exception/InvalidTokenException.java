package com.choruskube.core.exception;

public class InvalidTokenException extends BadRequestException {

    public InvalidTokenException(String message) {
        super(message);
    }
}
