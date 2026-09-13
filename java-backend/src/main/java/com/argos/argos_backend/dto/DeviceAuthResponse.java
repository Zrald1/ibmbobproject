package com.argos.argos_backend.dto;

public record DeviceAuthResponse(
		
				boolean success,
				String deviceId,
				String accessToken,
				boolean isNewDevice,
				String message
		
){

}
