package com.mm.wusd.bridge.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

  public record ApiError(
    String code,
    String message) {
  }

  @ExceptionHandler(DepositNotFoundException.class)
  public ResponseEntity<ApiError> notFound(
    DepositNotFoundException ex) {

    return ResponseEntity
      .status(HttpStatus.NOT_FOUND)
      .body(new ApiError(
        "DEPOSIT_NOT_FOUND",
        ex.getMessage()));
  }

  @ExceptionHandler(DepositConflictException.class)
  public ResponseEntity<ApiError> conflict(
    DepositConflictException ex) {

    return ResponseEntity
      .status(HttpStatus.CONFLICT)
      .body(new ApiError(
        "DEPOSIT_CONFLICT",
        ex.getMessage()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ApiError> validation(
    MethodArgumentNotValidException ex) {

    return ResponseEntity
      .status(HttpStatus.BAD_REQUEST)
      .body(new ApiError(
        "INVALID_REQUEST",
        "Deposit request validation failed"));
  }
}
