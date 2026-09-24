package com.akpedia.server.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

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

    /** 400: a required request parameter or multipart part is missing. */
    @ExceptionHandler({MissingServletRequestParameterException.class, MissingServletRequestPartException.class})
    public ResponseEntity<ApiErrorResponse> handleMissingParameter(Exception e) {
        return ResponseEntity.badRequest().body(new ApiErrorResponse("invalid_request", e.getMessage()));
    }

    /** 400: a request parameter could not be converted to the type the handler expects. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.badRequest().body(new ApiErrorResponse(
                "invalid_request", "%s deve ser um valor valido.".formatted(e.getName())));
    }

    /** 400: the upload was refused here, before any lookup or conversion was attempted. */
    @ExceptionHandler(InvalidDocumentUploadException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidUpload(InvalidDocumentUploadException e) {
        return ResponseEntity.badRequest().body(new ApiErrorResponse("invalid_request", e.getMessage()));
    }

    /** 400: the search text or result limit is invalid. */
    @ExceptionHandler(InvalidSearchRequestException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidSearch(InvalidSearchRequestException e) {
        return ResponseEntity.badRequest().body(new ApiErrorResponse("invalid_request", e.getMessage()));
    }

    /** 404: the category named in a document upload does not exist. */
    @ExceptionHandler(CategoryNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleCategoryNotFound(CategoryNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiErrorResponse("category_not_found", e.getMessage()));
    }

    /** 404: the creator named in a document upload does not exist. */
    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleUserNotFound(UserNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiErrorResponse("user_not_found", e.getMessage()));
    }

    /** 503: Gotenberg is down, unreachable, or past the read timeout. */
    @ExceptionHandler(PdfConversionUnavailableException.class)
    public ResponseEntity<ApiErrorResponse> handlePdfConversionUnavailable(PdfConversionUnavailableException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiErrorResponse("pdf_conversion_unavailable", e.getMessage()));
    }

    /**
     * Passes a Gotenberg refusal through as 422: the caller's file could not be converted,
     * which is about their upload, not about this server.
     */
    @ExceptionHandler(PdfConversionRejectedException.class)
    public ResponseEntity<ApiErrorResponse> handlePdfConversionRejected(PdfConversionRejectedException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ApiErrorResponse("pdf_conversion_rejected", e.getMessage()));
    }

    /** 502: Gotenberg answered, but not in a shape this server can use. */
    @ExceptionHandler(PdfConversionException.class)
    public ResponseEntity<ApiErrorResponse> handlePdfConversionFailure(PdfConversionException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ApiErrorResponse("pdf_conversion_failed", e.getMessage()));
    }

}
