package com.akpedia.server.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import com.akpedia.server.dto.ApiErrorResponse;
import com.akpedia.server.dto.EmbeddingErrorResponse;

/**
 * Turns the failures of the embedding integration into HTTP answers.
 *
 * <p>The rule is that the caller should be able to tell, from the status alone, whether
 * retrying makes sense: 503 means the downstream service was not there and the same
 * request may work later, while a 4xx means the request itself was refused and will keep
 * being refused.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    /** 503: the embedding service is down, unreachable, or past the read timeout. */
    @ExceptionHandler(EmbeddingUnavailableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnavailable(EmbeddingUnavailableException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiErrorResponse("embedding_service_unavailable", e.getMessage()));
    }

    /**
     * Passes a downstream refusal through with its own status and code.
     *
     * <p>A 415 for an unsupported format is about the caller's file, not about this server,
     * so answering 502 would be a lie. Only a downstream 5xx becomes a 502: there the
     * caller's request was fine and the failure is ours to explain.
     */
    @ExceptionHandler(EmbeddingRejectedException.class)
    public ResponseEntity<ApiErrorResponse> handleRejected(EmbeddingRejectedException e) {
        HttpStatusCode downstream = e.getStatusCode();
        HttpStatus status = downstream.is4xxClientError()
                ? HttpStatus.valueOf(downstream.value())
                : HttpStatus.BAD_GATEWAY;

        EmbeddingErrorResponse error = e.getError();
        ApiErrorResponse body = error != null
                ? new ApiErrorResponse(error.code(), error.message(), error.supportedExtensions())
                : new ApiErrorResponse("embedding_service_error", e.getMessage());

        return ResponseEntity.status(status).body(body);
    }

    /** 502: the service answered, but not in a shape this server can use. */
    @ExceptionHandler(EmbeddingException.class)
    public ResponseEntity<ApiErrorResponse> handleEmbeddingFailure(EmbeddingException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ApiErrorResponse("embedding_service_error", e.getMessage()));
    }

    /** 413: the upload is past this server's own multipart limit, so it never left here. */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiErrorResponse> handleTooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(new ApiErrorResponse("file_too_large", "The uploaded file is larger than the accepted limit."));
    }

    /** 400: the request body did not pass validation before any call was made. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidBody(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> "%s %s.".formatted(error.getField(), error.getDefaultMessage()))
                .orElse("The request body is invalid.");
        return ResponseEntity.badRequest().body(new ApiErrorResponse("invalid_request", detail));
    }

}
