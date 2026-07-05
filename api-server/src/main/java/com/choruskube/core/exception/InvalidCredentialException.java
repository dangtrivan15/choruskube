package com.choruskube.core.exception;

public class InvalidCredentialException extends BadRequestException {
    public InvalidCredentialException(String message) {
        super(message);
    }
}
