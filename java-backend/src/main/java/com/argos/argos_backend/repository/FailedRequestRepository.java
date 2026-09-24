package com.argos.argos_backend.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.argos.argos_backend.entity.FailedRequest;

@Repository
public interface FailedRequestRepository extends JpaRepository<FailedRequest, Long> {
    List<FailedRequest> findTop20ByOrderByCreatedAtDesc();
}
