package com.example.zavelo.push;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface PushSubscriptionRepository extends JpaRepository<PushSubscription, Long> {
    List<PushSubscription> findByUserId(Long userId);
    Optional<PushSubscription> findByEndpoint(String endpoint);
    boolean existsByUserId(Long userId);
    long countByUserId(Long userId);

    @Transactional
    long deleteByEndpoint(String endpoint);

    @Transactional
    long deleteByEndpointAndUserId(String endpoint, Long userId);
}
