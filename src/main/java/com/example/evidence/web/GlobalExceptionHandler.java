package com.example.evidence.web;

import com.example.evidence.plan.PlanException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(PlanException.class)
    public ResponseEntity<ApiDtos.ErrorResponse> handlePlanException(PlanException e) {
        return ResponseEntity.status(e.getHttpStatus())
                .body(new ApiDtos.ErrorResponse(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiDtos.ErrorResponse> handleUnexpected(Exception e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiDtos.ErrorResponse("INTERNAL_ERROR", e.getMessage()));
    }
}
