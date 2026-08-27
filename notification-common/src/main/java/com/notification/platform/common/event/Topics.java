package com.notification.platform.common.event;

/** Topic names, in one place so the api and the worker cannot drift apart. */
public final class Topics {

    public static final String NOTIFICATION_REQUESTED = "notification.requested";

    /**
     * Where {@code @RetryableTopic} parks a message that exhausted its retries. The suffix
     * is Spring Kafka's default, spelled out here so the name is greppable and so the
     * compose stack can pre-create it.
     */
    public static final String NOTIFICATION_REQUESTED_DLT = NOTIFICATION_REQUESTED + "-dlt";

    private Topics() {
    }
}
