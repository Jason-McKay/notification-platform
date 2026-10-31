package com.notification.platform.worker.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.notification.platform.common.event.NotificationChannel;
import com.notification.platform.worker.domain.DeliveryStatus;
import com.notification.platform.worker.domain.Notification;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

  /** The delivery list. A null filter matches everything; the sort comes from the page. */
  @Query("""
      select n from Notification n
      where (:status is null or n.status = :status)
        and (:channel is null or n.channel = :channel)
        and (:recipientId is null or n.recipientId = :recipientId)
      """)
  Page<Notification> search(@Param("status") DeliveryStatus status,
      @Param("channel") NotificationChannel channel, @Param("recipientId") String recipientId,
      Pageable pageable);

  @Query("""
      select n.status as status, count(n) as total from Notification n
      where n.updatedAt >= :since
      group by n.status
      """)
  List<StatusCount> countByStatusSince(@Param("since") Instant since);

  @Query("""
      select n.channel as channel, count(n) as total from Notification n
      where n.updatedAt >= :since
      group by n.channel
      """)
  List<ChannelCount> countByChannelSince(@Param("since") Instant since);

  interface StatusCount {

    DeliveryStatus getStatus();

    long getTotal();
  }

  interface ChannelCount {

    NotificationChannel getChannel();

    long getTotal();
  }
}
