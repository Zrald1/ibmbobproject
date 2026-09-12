package com.argos.argos_backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DeviceAuthRequest(
				
		
			@NotBlank(message = "deviceId is required")
			@Size(max=255, message = "deviceId must not exceed 255 characters")
			String deviceId,
			
			@Size(max=100, message = "deviceName must not exceed 100 characters")
			String deviceName
			
		
){

}