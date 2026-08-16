package com.choruskube.core.exception;

import com.choruskube.core.dto.ActiveRunsConflictResponse;
import com.choruskube.core.dto.ValidationResponse;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mapping.PropertyReferenceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@ControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<String> handleNotFound(NotFoundException ex) {
        return new ResponseEntity<>(ex.getMessage(), HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(ActiveRunsConflictException.class)
    public ResponseEntity<ActiveRunsConflictResponse> handleActiveRunsConflict(ActiveRunsConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ActiveRunsConflictResponse(ex.getMessage(), ex.getActiveRunCount()));
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<String> handleConflict(ConflictException ex) {
        return new ResponseEntity<>(ex.getMessage(), HttpStatus.CONFLICT);
    }

    @ExceptionHandler(QuotaExceededException.class)
    public ResponseEntity<String> handleQuotaExceeded(QuotaExceededException ex) {
        return new ResponseEntity<>(ex.getMessage(), HttpStatus.TOO_MANY_REQUESTS);
    }

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ValidationResponse> handleValidation(ValidationException ex) {
        return ResponseEntity.badRequest().body(new ValidationResponse(false, ex.getErrors()));
    }

    /**
     * Invalid sort field names (e.g. ?sort=nonexistent,asc) cause Spring Data to throw
     * PropertyReferenceException. Map these to 400 Bad Request with a clear message so
     * clients get actionable feedback instead of a generic 500.
     */
    @ExceptionHandler(PropertyReferenceException.class)
    public ResponseEntity<String> handlePropertyReference(PropertyReferenceException ex) {
        return new ResponseEntity<>(
                "Invalid sort property: " + ex.getPropertyName() + ". " + ex.getMessage(), HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<String> handleBadRequest(BadRequestException ex) {
        return new ResponseEntity<>(ex.getMessage(), HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<String> handleForbidden(ForbiddenException ex) {
        return new ResponseEntity<>(ex.getMessage(), HttpStatus.FORBIDDEN);
    }

    @ExceptionHandler(AuthorizationDeniedException.class)
    public ResponseEntity<String> handleAuthorizationDenied(AuthorizationDeniedException ex) {
        return new ResponseEntity<>(ex.getMessage(), HttpStatus.FORBIDDEN);
    }

    @ExceptionHandler(UnresolvableTenantException.class)
    public ResponseEntity<Map<String, String>> handleUnresolvableTenant(UnresolvableTenantException ex) {
        return new ResponseEntity<>(Map.of("error", ex.getMessage()), HttpStatus.FORBIDDEN);
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<String> handleRuntime(RuntimeException ex) {
        log.error("Unhandled exception", ex);
        return new ResponseEntity<>("Internal server error", HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
