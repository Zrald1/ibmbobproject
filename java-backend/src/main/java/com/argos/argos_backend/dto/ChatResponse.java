package com.argos.argos_backend.dto;

public record ChatResponse(
			
		boolean success,
		String source,
		String reply,
		ActionDto action,
		boolean retrySuggested
		
) {
	
}
