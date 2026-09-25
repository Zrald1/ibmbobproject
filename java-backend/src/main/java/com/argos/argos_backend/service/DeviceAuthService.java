package com.argos.argos_backend.service;

import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.argos.argos_backend.dto.DeviceAuthRequest;
import com.argos.argos_backend.dto.DeviceAuthResponse;
import com.argos.argos_backend.entity.Device;
import com.argos.argos_backend.repository.DeviceRepository;

/**
 * Registers and authenticates Argos devices, issuing a bearer token and
 * tracking last-seen time. The response conforms to {@code backend/README.md}
 * while retaining the legacy camelCase fields for older clients.
 */
@Service
public class DeviceAuthService {

    private final DeviceRepository deviceRepository;

    public DeviceAuthService(DeviceRepository deviceRepository) {
        this.deviceRepository = deviceRepository;
    }

    @Transactional
    public DeviceAuthResponse authenticate(DeviceAuthRequest request) {
        String deviceId = request.deviceId() == null ? "" : request.deviceId().trim();
        String deviceName = (request.deviceName() == null || request.deviceName().isBlank())
                ? null : request.deviceName().trim();

        Device existing = deviceRepository.findByDeviceId(deviceId).orElse(null);
        if (existing != null) {
            existing.setLastSeenAt(LocalDateTime.now());
            if (deviceName != null) {
                existing.setDeviceName(deviceName);
            }
            deviceRepository.save(existing);
            return toResponse(existing, false, "Existing ARGOS device authenticated successfully");
        }

        String token = "argos_" + UUID.randomUUID();
        Device device = new Device(deviceId, deviceName, token);
        deviceRepository.save(device);
        return toResponse(device, true, "New ARGOS device registered successfully");
    }

    private DeviceAuthResponse toResponse(Device device, boolean isNew, String message) {
        String username = device.getDeviceName() != null && !device.getDeviceName().isBlank()
                ? device.getDeviceName()
                : device.getDeviceId();
        DeviceAuthResponse.User user = new DeviceAuthResponse.User(device.getId(), username);
        return new DeviceAuthResponse(
                true,
                device.getAccessToken(),
                "bearer",
                device.getDeviceId(),
                user,
                message,
                isNew);
    }
}
