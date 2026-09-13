package com.argos.argos_backend.service;

import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.argos.argos_backend.dto.DeviceAuthRequest;
import com.argos.argos_backend.dto.DeviceAuthResponse;
import com.argos.argos_backend.entity.Device;
import com.argos.argos_backend.repository.DeviceRepository;

@Service
public class DeviceAuthService {
			private final DeviceRepository deviceRepository;
			public DeviceAuthService(DeviceRepository deviceRepository){
				this.deviceRepository = deviceRepository;
			}
			
			public DeviceAuthResponse authenticate(DeviceAuthRequest request){
				Device existingDevice = deviceRepository.findByDeviceId(request.deviceId()).orElse(null); 
				
				if(existingDevice != null){
				
					existingDevice.setLastSeenAt(LocalDateTime.now());
				
					if(request.deviceName() != null && !request.deviceName().isBlank()){
						existingDevice.setDeviceName(request.deviceName().trim());
					}
					deviceRepository.save(existingDevice);
					
					return new DeviceAuthResponse(
							true,
							existingDevice.getDeviceId(),
							existingDevice.getAccessToken(),
							false,
							"Exisiting ARGOS device authenticated successfully"
					);
				}
				
				
				String token = "argos_" + UUID.randomUUID(); 
				
				Device newDevice = new Device(request.deviceId().trim(),
											  request.deviceName() == null ? null : request.deviceName().trim(),
											  token
											 );
				
				deviceRepository.save(newDevice);
				
				return new DeviceAuthResponse(
						true,
						newDevice.getDeviceId(),
						newDevice.getAccessToken(),
						true,
						" New ARGOS device registered successfully"
						);
				
			}
}
