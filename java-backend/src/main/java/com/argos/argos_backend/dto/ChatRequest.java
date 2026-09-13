package com.argos.argos_backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChatRequest(
	
		@NotBlank(message = "deviceId is required")
		String deviceId,
		
		@NotBlank(message = "message is required")
		@Size(max=2000 , message="message must not exceed 2000 characters")
		String message,
		
		@Size(max=6000, message = "screenContext must not exceed 6000 characters")
		String screenContext
) {
	
}
