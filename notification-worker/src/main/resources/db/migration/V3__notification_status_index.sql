-- The delivery list filtered by status, newest first: equality on status, then updated_at,
-- with InnoDB's implicit event_id suffix as the tiebreak, so the page needs no filesort.
-- The unfiltered list and the stats range over updated_at alone and are not served by it;
-- at this scale a scan is fine, and an updated_at index is the next step if it stops being.

-- RETRYING and DEAD_LETTERED needed no change to the column itself. status is varchar(16)
-- on both tables, nothing constrains its values, and Hibernate's validate checks neither
-- the enum's constants nor the column's length. A status longer than 16 characters would
-- need both columns widened before it ships; DeliveryStatusTest pins that.
create index idx_notification_status_updated on notification (status, updated_at);
