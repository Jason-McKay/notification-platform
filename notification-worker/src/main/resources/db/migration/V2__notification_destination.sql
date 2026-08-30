-- Where a notification is delivered: an address, a phone number, a device token. Distinct
-- from recipient_id, which is who it is for and stays opaque to this platform. The two were
-- one field until EMAIL got a real sender and the difference started to matter.

-- 320 characters is the longest an email address can be (64 local part, @, 255 domain), and
-- it is comfortably above what a phone number or a device token needs.
alter table notification
    add column destination varchar(320) not null after recipient_id;
