package com.argos.argos_backend.repository;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.argos.argos_backend.entity.CachedResponse;

@Repository
public interface CachedResponseRepository extends JpaRepository<CachedResponse, Long> {

    Optional<CachedResponse> findByQueryHash(String queryHash);

    long deleteByExpiresAtBefore(Instant cutoff);
}
