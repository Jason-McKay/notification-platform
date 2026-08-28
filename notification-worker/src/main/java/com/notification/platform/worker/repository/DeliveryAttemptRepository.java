package com.notification.platform.worker.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.notification.platform.worker.domain.DeliveryAttempt;

public interface DeliveryAttemptRepository extends JpaRepository<DeliveryAttempt, Long> {

  List<DeliveryAttempt> findByEventIdOrderByAttemptedAtAsc(UUID eventId);
}
