
package com.fde.transaction.exception;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
//import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;


import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
        request = mock(HttpServletRequest.class);

        when(request.getRequestURI()).thenReturn("/api/transactions");
    }

    @Test
    void shouldReturn404WhenResourceNotFound() {

        ResourceNotFoundException exception =
                new ResourceNotFoundException("Transaction not found");

        var response = handler.handleNotFound(exception, request);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("Transaction not found",
                response.getBody().message());
    }

    @Test
    void shouldReturn409ForIllegalState() {

        IllegalStateException exception =
                new IllegalStateException("Duplicate transaction");

        var response = handler.handleIllegalState(exception, request);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("Duplicate transaction",
                response.getBody().message());
    }

    @Test
    void shouldReturn409ForDatabaseConstraintViolation() {

        DataIntegrityViolationException exception =
                new DataIntegrityViolationException("Unique constraint violation");

        var response = handler.handleDataIntegrity(exception, request);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("Request conflicts with existing transaction data",
                response.getBody().message());
    }

    @Test
    void shouldReturn400ForInvalidJson() {

    	HttpMessageNotReadableException exception =
    	        new HttpMessageNotReadableException(
    	                "Invalid JSON",
    	                new MockHttpInputMessage(new byte[0])
    	        );

        var response = handler.handleInvalidJson(exception, request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("Invalid JSON request or unsupported field value",
                response.getBody().message());
    }

    @Test
    void shouldReturn500ForUnexpectedException() {

        Exception exception = new RuntimeException("Internal error");

        var response = handler.handleGeneric(exception, request);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR,
                response.getStatusCode());

        assertEquals("An unexpected error occurred",
                response.getBody().message());
    }
}
