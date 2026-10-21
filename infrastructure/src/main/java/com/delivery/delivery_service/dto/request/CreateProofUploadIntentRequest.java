package com.delivery.delivery_service.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/** Metadata used to constrain a private, signed POD upload. */
@Data
public class CreateProofUploadIntentRequest {
    @NotBlank(message = "contentType is required")
    @Pattern(regexp = "image/(jpeg|png|webp)", message = "contentType must be image/jpeg, image/png or image/webp")
    private String contentType;

    @Min(value = 1, message = "contentLengthBytes must be positive")
    @Max(value = 10 * 1024 * 1024, message = "proof image must be at most 10 MB")
    private long contentLengthBytes;
}
