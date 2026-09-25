package com.argos.argos_backend.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.argos.argos_backend.entity.ChatMemory;

@Repository
public interface ChatMemoryRepository extends JpaRepository<ChatMemory, Long> {

    /** Most recent turns for a device, newest first. */
    List<ChatMemory> findTop40ByDeviceIdOrderByCreatedAtDesc(String deviceId);

    long countByDeviceId(String deviceId);
}
