# 5. Delivery states, the read api, and what the metrics expose

- **Status:** Accepted
- **Date decided:** 2026-10-31
- **Date recorded:** 2026-10-31, with the list, stats and metrics endpoints

## Context

A delivery dashboard is coming, and it should be a thin client: everything it shows has to
come from the worker, which owns the delivery records (ADR 0003). Three things stood in the
way.

- **The status model could not tell "trying again" from "given up".** FAILED was written on
  every failed try, so an event working its way through the retry topics and an event parked
  on the dead-letter topic read the same. That was a listed known gap, and a dashboard built
  on it would show a red row for something that was about to succeed.
- **There was only a read by id.** A dashboard needs a page of recent deliveries, filtered,
  and a summary.
- **There were no delivery metrics**, only Boot's defaults.

The retry policy itself was not up for change: the retry topics are named after the backoff
delay (`notification.requested-retry-1000`, `-retry-2000`), so changing the backoff renames
topics.

## Decision

### Five states, and who sets each

| Status | Set by | Means |
|---|---|---|
| PENDING | the claim | accepted from the topic, not yet sent |
| SENT | `recordSent` | the provider took it |
| RETRYING | the listener, on a failed try with tries left | a retry topic holds another try |
| FAILED | the listener, on the last failed try | on its way to the DLT |
| DEAD_LETTERED | the `@DltHandler` | parked; nothing will try it again without a redrive |

- **The listener knows which try it is on** from the `retry_topic-attempts` header, which
  the retry machinery writes as it forwards a record: absent on the main topic, then 2, 3.
  A try with `attempt < notification.consumer.attempts` (the same property `@RetryableTopic`
  reads) records RETRYING, the last one FAILED.
- **The DLT handler is the final authority.** It marks the row DEAD_LETTERED and appends an
  attempt row saying so ("parked on notification.requested-dlt"), so the history shows the
  event was parked rather than only that its tries failed. It is idempotent: a redelivered
  DLT record finds DEAD_LETTERED and changes nothing, and a SENT row is never overwritten.
- **FAILED is therefore brief**, the gap between the last try and the DLT handler. An
  exception the retry machinery classes as fatal skips the retry topics and goes straight to
  the DLT, so a row can read RETRYING for a few milliseconds before DEAD_LETTERED.
- **No column change.** `status` is `varchar(16)` on both tables, nothing constrains the
  values, and `ddl-auto=validate` checks neither the enum's constants nor the column's
  length; DEAD_LETTERED is 13 characters. Because validate would not catch a longer name,
  `DeliveryStatusTest` pins every status and channel to 16. V3 only adds
  `idx_notification_status_updated (status, updated_at)`, for the list filtered by status.

### The read api

On the worker, under the same `notifications.read` scope as the read by id:

- `GET /api/notifications?status=&channel=&recipientId=&page=&size=` -- a page, **newest
  first by `updated_at`** with `event_id` as the tiebreaker, so the order is total. Paging
  is 0-based, `size` defaults to 20 and is capped at 100 without an error; a negative page
  or a size below 1 is a 400. The sort is fixed: a sort the client chooses would need an
  index per column it can choose.
- Each item is a fixed summary: `eventId, recipientId, destination, channel, templateKey,
  status, requestedAt, updatedAt, attemptCount`. `attemptCount` counts delivery tries (the
  SENT and FAILED attempt rows), not the DEAD_LETTERED marker, so three failed tries read 3.
- `GET /api/notifications/stats` -- counts by status and by channel, and the total, over
  rows **updated** in the last 24 hours. Every status and channel is present, zeros
  included, so a client never has to tell "none" from "missing".
- `GET /api/notifications/{eventId}` keeps its shape and gains `destination`, `channel` and
  `templateKey`.

### Why the list omits the payload

- **It is not a stable contract.** The payload is a free-form map whose keys belong to the
  template, so its shape changes with every template and its size has no bound. A list
  response should be the same few fields for every row.
- **It is where personal data lives.** Template variables are names, order numbers,
  addresses. A list is the response most likely to be cached, logged by a proxy, and
  screenshotted into a bug report, so it carries the least.
- **A dashboard does not need it to render a row**, and the detail view does not expose it
  either -- it never has. Rendering the message is the worker's job; a client that needs the
  rendered copy is asking for a different endpoint.

### Metrics

- `notification.delivery` -- a counter tagged `channel` (`EMAIL`, `SMS`, `PUSH`, the same
  spelling as the api filter) and `outcome` (`sent`, `failed`, `dead_lettered`). `failed`
  counts every failed try, including the ones that will be retried; `dead_lettered` counts
  each event once.
- `notification.dispatch.duration` -- a timer tagged `channel`, around every dispatch,
  successful or not.
- **Cardinality is fixed by the enums**: three channels, three outcomes. Every series is
  registered at startup, so they read 0 rather than being absent until the first event.
  Nothing is ever tagged by recipient or event id. Boot's own `http.server.requests` tags by
  URI *template*, so an event id in the path does not leak into a tag there either.
- **Exposure: `/actuator/metrics` and `/actuator/prometheus` are on, behind the same
  `notifications.read` scope** as the read api. `/actuator/health` stays open. The
  Prometheus registry is the one dependency this added; the actuator starter already brings
  the export autoconfiguration.

## Consequences

- The dashboard can be built against this api without a backend of its own.
- **A Prometheus server needs a bearer token to scrape**, and dev tokens expire after an
  hour (`notification.auth.token-ttl=PT1H`). So compose ships no Prometheus: a scraper with
  a token it has to keep refreshing is worse than none. A service credential from a real IdP
  is what fixes it, and that is the API Gateway project's job.
- **Offset paging over a mutable sort key.** A row that changes status moves to the top, so
  a client paging while deliveries are happening can see a row twice or miss one. Acceptable
  for a dashboard that refreshes; not for an export.
- **The unfiltered list and the stats have no index led by `updated_at`.** The new index
  leads with `status`, so it serves the list filtered by status (equality, then
  `updated_at`, with InnoDB's implicit `event_id` suffix as the tiebreak) and nothing else:
  the unfiltered list sorts, and both stats queries scan. Fine at this scale; an
  `(updated_at)` index is the follow-up when it is not.
- **The new states are one-way.** They needed no schema change, but code from before this
  change cannot load a RETRYING or DEAD_LETTERED row: Hibernate fails on the unknown enum
  constant, which breaks the read by id and the claim of a redelivered event. Rolling back
  means mapping the rows first, by hand rather than as a migration:
  `update notification set status = 'FAILED' where status in ('RETRYING', 'DEAD_LETTERED')`,
  then `delete from delivery_attempt where status = 'DEAD_LETTERED'`.
- **A poison record reaches the DLT but leaves no row.** It never deserialized, so there is
  no event id to record against, and the DLT handler never sees it. Same as before.
- **A DLT handler that throws sends the record back to the DLT** (spring-kafka's default
  `ALWAYS_RETRY_ON_ERROR`). Left as it is: changing it is retry behaviour, and the handler
  only writes two rows.
- **Rows written before this change are not backfilled.** A notification that was parked
  before it still reads FAILED.

## Alternatives considered

- **Expose the payload in the list.** Rejected for the reasons above.
- **Cursor (keyset) paging on `(updated_at, event_id)`.** The right answer for an export or
  an infinite scroll; within a status filter the index already supports it, and without one
  it needs the `(updated_at)` index above. Deferred: offset paging is what a paged table
  wants, and a cursor can be added beside it without breaking it.
- **Derive RETRYING from the attempt count in the database** instead of the header. Rejected:
  it is a second count of the same thing. The header is what the retry machinery itself
  decides with, so the listener and the machinery cannot disagree about which try is last.
- **A denormalised `attempt_count` column.** Saves the group-by on each page, at the price of
  a second place that has to agree with the attempt rows. Not worth it at a page of 100.
- **Leave `/actuator/prometheus` open, or on a separate management port.** Either makes a
  scraper simple, and either publishes delivery volumes by channel to anything that can
  reach the port. The project's rule is that the worker's reads take the read scope; the
  metrics are a read.
