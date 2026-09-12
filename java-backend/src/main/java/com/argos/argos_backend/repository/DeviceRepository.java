package com.argos.argos_backend.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.argos.argos_backend.entity.Device;

@Repository
public interface DeviceRepository extends JpaRepository<Device, Integer> {
		
	Optional<Device> findByDeviceId(String deviceId);
}
