package com.argos.argos_backend.service;

import org.springframework.stereotype.Service;

import com.argos.argos_backend.dto.ChatRequest;
import com.argos.argos_backend.dto.ChatResponse;

@Service
public class ChatService {
	private final FallbackService fallbackService;
	
	public ChatService(FallbackService fallbackService){
			this.fallbackService = fallbackService;
	}
	
	public ChatResponse processChat(ChatRequest request) {
		try {
			return callPrimaryAi(request);
		}catch(Exception exception) {
			return fallbackService.createFallbackResponse(
					request.message(),
					request.screenContext()
			);
		}
	}
	
	private ChatResponse callPrimaryAi(ChatRequest request){
			throw new IllegalStateException("Primary AI provider is not configured yet.");
	}
	
	
	
}
