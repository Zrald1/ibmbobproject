package com.argos.argos_backend.dto;

public record ActionDto(
		
		String type,
		String value,
		boolean requiresConfirmation
		
		
) {
	
}