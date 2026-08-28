-- The worker owns this schema. Nothing else writes to it.

create table notification (
    event_id     binary(16)   not null,
    recipient_id varchar(128) not null,
    channel      varchar(16)  not null,
    template_key varchar(128) not null,
    payload      text         not null,
    status       varchar(16)  not null,
    requested_at datetime(6)  not null,
    updated_at   datetime(6)  not null,
    primary key (event_id),
    -- The event id from the topic is the primary key on purpose: a Kafka redelivery
    -- collides with the row already here instead of sending a second notification.
    key idx_notification_recipient (recipient_id, requested_at)
) engine = InnoDB;

create table delivery_attempt (
    id           bigint       not null auto_increment,
    event_id     binary(16)   not null,
    status       varchar(16)  not null,
    detail       varchar(512),
    attempted_at datetime(6)  not null,
    primary key (id),
    key idx_delivery_attempt_event (event_id),
    constraint fk_delivery_attempt_notification
        foreign key (event_id) references notification (event_id)
) engine = InnoDB;
