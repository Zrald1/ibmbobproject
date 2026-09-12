package com.argos.argos_backend.dto;

import java.time.Instant;
import java.util.Map;

public record ApiErrorResponse(
		
		boolean success,
		String message,
		int status,
		String path,
		Instant timestamp,
		Map<String,String> validationErrors
		
	
){
	
}
