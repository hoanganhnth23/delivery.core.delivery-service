package com.delivery.delivery_service.exception;

import com.delivery.delivery_service.payload.BaseResponse;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void domainExceptionsMapToTheirStatusesAndKeepMessages() {
        assertError(handler.handleNotFound(new ResourceNotFoundException("Không tìm thấy delivery")),
                HttpStatus.NOT_FOUND, "Không tìm thấy delivery");
        assertError(handler.handleAccessDenied(new AccessDeniedException("Không phải shipper được giao")),
                HttpStatus.FORBIDDEN, "Không phải shipper được giao");
        assertError(handler.handleInvalidStatus(new InvalidStatusException("Không thể chuyển trạng thái")),
                HttpStatus.BAD_REQUEST, "Không thể chuyển trạng thái");
    }

    @Test
    void proofStorageOutageAndUnexpectedErrorsHideInternalDetails() {
        assertError(handler.handleProofStorageUnavailable(new ProofStorageUnavailableException("s3 bucket down")),
                HttpStatus.SERVICE_UNAVAILABLE, "Dịch vụ lưu bằng chứng giao hàng chưa sẵn sàng");
        assertError(handler.handleAll(new RuntimeException("NPE at DeliveryServiceImpl")),
                HttpStatus.INTERNAL_SERVER_ERROR, "Đã xảy ra lỗi nội bộ.");
    }

    @Test
    void beanValidationReportsFirstFieldErrorOrGenericMessage() throws Exception {
        MethodParameter parameter = new MethodParameter(
                GlobalExceptionHandlerTest.class.getDeclaredMethod("beanValidationReportsFirstFieldErrorOrGenericMessage"), -1);
        BeanPropertyBindingResult withErrors = new BeanPropertyBindingResult(new Object(), "request");
        withErrors.addError(new FieldError("request", "reason", "must not be blank"));
        withErrors.addError(new FieldError("request", "deliveryId", "must be positive"));
        assertError(handler.handleValidation(new MethodArgumentNotValidException(parameter, withErrors)),
                HttpStatus.BAD_REQUEST, "reason: must not be blank");

        BeanPropertyBindingResult noFieldErrors = new BeanPropertyBindingResult(new Object(), "request");
        assertError(handler.handleValidation(new MethodArgumentNotValidException(parameter, noFieldErrors)),
                HttpStatus.BAD_REQUEST, "Invalid request");
    }

    private static void assertError(ResponseEntity<BaseResponse<Object>> response, HttpStatus status, String message) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody().getStatus()).isZero();
        assertThat(response.getBody().getData()).isNull();
        assertThat(response.getBody().getMessage()).isEqualTo(message);
    }
}
