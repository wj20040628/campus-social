package com.campus.social.exception;

import com.campus.social.common.Result;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    @Test
    void maxUploadSizeReturns413Not500() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        ResponseEntity<Result<?>> resp = handler.maxUploadSize(new MaxUploadSizeExceededException(10485760));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getCode()).isEqualTo(413);
        assertThat(resp.getBody().getMessage()).contains("10MB");
    }
}