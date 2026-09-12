package com.argos.argos_backend.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.argos.argos_backend.dto.DeviceAuthRequest;
import com.argos.argos_backend.dto.DeviceAuthResponse;
import com.argos.argos_backend.service.DeviceAuthService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
			private final DeviceAuthService deviceAuthService;
			public AuthController(DeviceAuthService deviceAuthService) {
				this.deviceAuthService = deviceAuthService;
			}
			
			@PostMapping("/device")
			public ResponseEntity<DeviceAuthResponse> authenticateDevice(@Valid @RequestBody DeviceAuthRequest request){
				return ResponseEntity.ok(deviceAuthService.authenticate(request));
			}
}
