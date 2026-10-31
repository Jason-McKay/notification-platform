package com.notification.platform.worker.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.notification.platform.worker.domain.DeliveryAttempt;

public interface DeliveryAttemptRepository extends JpaRepository<DeliveryAttempt, Long> {

  List<DeliveryAttempt> findByEventIdOrderByAttemptedAtAsc(UUID eventId);

  /**
   * Delivery tries per event, for one page of the list. The DEAD_LETTERED row marks the
   * event as parked rather than recording a try, so it is not counted.
   */
  @Query("""
      select a.eventId as eventId, count(a) as total from DeliveryAttempt a
      where a.eventId in :eventIds
        and a.status <> com.notification.platform.worker.domain.DeliveryStatus.DEAD_LETTERED
      group by a.eventId
      """)
  List<TryCount> countTries(@Param("eventIds") Collection<UUID> eventIds);

  interface TryCount {

    UUID getEventId();

    long getTotal();
  }
}
