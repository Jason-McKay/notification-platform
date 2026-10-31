package com.notification.platform.worker.domain;

import org.junit.jupiter.api.Test;

import com.notification.platform.common.event.NotificationChannel;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The enums stored as strings must fit their varchar(16) columns. Hibernate's validate
 * checks neither the constants nor the length, so without this a long name would first
 * fail as "Data too long" on a write -- for a parking state, inside the DLT handler.
 */
class DeliveryStatusTest {

  private static final int STATUS_COLUMN_WIDTH = 16;
  private static final int CHANNEL_COLUMN_WIDTH = 16;

  @Test
  void everyStatusFitsTheColumn() {
    assertThat(DeliveryStatus.values())
        .allSatisfy(status ->
            assertThat(status.name()).hasSizeLessThanOrEqualTo(STATUS_COLUMN_WIDTH));
  }

  @Test
  void everyChannelFitsTheColumn() {
    assertThat(NotificationChannel.values())
        .allSatisfy(channel ->
            assertThat(channel.name()).hasSizeLessThanOrEqualTo(CHANNEL_COLUMN_WIDTH));
  }
}
