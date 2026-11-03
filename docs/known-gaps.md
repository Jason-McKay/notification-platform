# Known gaps

Moved out of `CLAUDE.md` on 2026-10-31, word for word, so it no longer loads into every
session. `CLAUDE.md` keeps each rule as a line or two and points here for the reason. Where the
text below says "this file", it means `CLAUDE.md` as it stood when the passage was written.

- The dev token endpoint (`POST /api/auth/token`) is a stand-in for an identity provider:
  one configured client credential, HS256, no user store. Both applications share
  `notification.jwt.secret`, which is what symmetric signing costs. An IdP with JWKS is the
  API Gateway project's job.
- **Scopes separate the routes, not the callers.** `notifications.write` guards the api's
  POST and `notifications.read` the worker's GET (`Scopes` in `notification-common`, so the
  minting side and the two verifying sides cannot drift), but there is one client credential
  and it is minted both. Different clients getting different scopes needs the IdP above.
- EMAIL is real and talks SMTP; SMS and PUSH are still `LoggingNotificationSender`
  stand-ins. Swapping one of those for a provider is a bean in `SenderConfig` and nothing
  else.
- Email is plain text, one subject and one body. No HTML, no multipart, no attachments.
- Nothing validates the shape of `destination`. A malformed address is a provider
  rejection like any other: recorded on the attempt, retried, dead-lettered.
- Templates are configuration (`notification.templates.*`), not a store, so changing copy is
  a redeploy. `TemplateRenderer` takes them as a map, so a store would not reach it.
- The api accepts any `templateKey`: it owns no templates, so an unknown key is a 202
  followed by three failed attempts and a dead-lettered event. Validating at the edge means
  giving the api a copy of the template keys, which is a contract to keep in step.
- A poison record (one that never deserializes) reaches the DLT but leaves no row: there is
  no event id to record against, and the `@DltHandler` never sees it. The status read
  answers 404 for it, as before.
- The list pages by offset over `updated_at`, which changes, so a client paging while
  deliveries are happening can see a row twice or miss one. Fine for a dashboard that
  refreshes; an export would want keyset paging (ADR 0005).
- A `@DltHandler` that throws sends the record back to the DLT (spring-kafka's default
  `ALWAYS_RETRY_ON_ERROR`). Left alone because it is retry behaviour; the handler only
  writes two rows.
- Prometheus needs a bearer token to scrape, and dev tokens last an hour, so compose runs no
  Prometheus server. A service credential is the IdP's job.
- Rows parked before the RETRYING/DEAD_LETTERED states existed still read `FAILED`; nothing
  backfills them. The new states are also one-way: older code cannot load them, so a
  rollback maps them back by hand first (the SQL is in ADR 0005).
- The retry topics are created by Spring Kafka on demand and named after the backoff delay
  (`notification.requested-retry-1000`, `-retry-2000`); only the main topic and the DLT are
  declared as beans.
- A crash between the send and the SENT record leaves the row PENDING with the message
  already delivered, and the retry sends it again (ADR 0004). At-least-once, deliberately.
- **The main-topic partition race is closed (2026-10-04).** `notification.requested`
  (3 partitions) is now declared as a `NewTopic` bean in *both* applications
  (`KafkaTopicConfig` in the api and in the worker), so whichever boots first creates it with
  the full partition count and neither side relies on broker auto-creation. Before this, only
  the api declared it: a worker whose consumer reached the broker first cached the
  one-partition auto-created topic and kept consuming partition 0 alone until
  `metadata.max.age.ms` (5 minutes) expired, stranding events keyed onto the other partitions
  (seen on a compose run on 2026-08-30). The worker ITs run `@EmbeddedKafka` at 3 partitions
  to match. **The DLT is still declared only by the api**, so the same race remains possible
  for `notification.requested-dlt` in principle; it is a narrower window (the DLT is not on
  the consume path and is created by the worker's `@RetryableTopic` machinery), left as a
  residual gap.
- **An `Idempotency-Key` names its event permanently, and globally** (ADR 0006). The event id
  is derived from the key alone, so a key reused after Redis's 24h TTL derives the original
  id: the worker absorbs it and the api answers 202 `duplicate=false`, and nothing is sent.
  Keys must be unique per notification. They are also not scoped to the client, which is moot
  with one client credential; with the IdP above, the derivation and the Redis key need to be
  over the client and the key together.
- **A 5xx from the POST means "not confirmed", not "not sent"** (ADR 0006). A publish that
  timed out may still land; the client must retry under the same key, and the worker absorbs
  the copy if it did. A copy that meets a retry of the first one from a retry topic can still
  send twice, as at-least-once allows.
- Reusing a key with a different body is not detected: the second body is ignored.
- Concurrent requests under one key can both publish. They carry the same event id, so the
  worker absorbs the copy and only one caller is told `duplicate=false`.
- Redis down *before* the publish fails the request (5xx) and publishes nothing. Deliberate:
  publishing would be safe for delivery, but a replay would be answered `duplicate=false`.
- Rolling out ADR 0006 has a window: an old instance still claims before publishing, so until
  the last one stops, and for up to 24h after, a claim it left behind can still swallow a
  retry.
- `NotificationEventPublisher` awaits the ack for 5s, but `KafkaTemplate.send` can first block
  for up to `max.block.ms` (60s) fetching metadata, so a dead broker holds a request thread for
  about a minute before the 5xx.
- OWASP Dependency-Check is not wired in; add it with the `dep-audit` skill (needs the
  `NVD_API_KEY` secret) once the dependency set settles.
