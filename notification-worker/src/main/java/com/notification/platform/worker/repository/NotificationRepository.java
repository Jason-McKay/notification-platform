package com.notification.platform.worker.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.notification.platform.worker.domain.Notification;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {
}
