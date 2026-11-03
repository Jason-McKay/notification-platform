# Testing notes

Moved out of `CLAUDE.md` on 2026-10-31, word for word, so it no longer loads into every
session. `CLAUDE.md` keeps each rule as a line or two and points here for the reason. Where the
text below says "this file", it means `CLAUDE.md` as it stood when the passage was written.

- **`./mvnw test` does not run the `*IT` classes.** That is deliberate: `test` stays fast
  and Docker-free. `./mvnw verify` runs them under failsafe.
- `NotificationDispatchIT` (worker) is the walking skeleton: event on the broker → row in
  MySQL through Flyway's schema. `NotificationIngressIT` (api) boots the real context and
  asserts an accepted request reaches the topic.
- **Kafka in the ITs is spring-kafka's `@EmbeddedKafka`, not Testcontainers.** Both
  Testcontainers Kafka flavours (`apache/kafka`, `confluentinc/cp-kafka`) fail to start here:
  they set `listeners` to `0.0.0.0`, and the image formats storage before the broker starts,
  which rejects 0.0.0.0 as an advertised address. The containers exit 1 and the wait strategy
  times out. MySQL and Redis containers work fine and are used.
- `NotificationDispatchIT.poisonRecordEndsUpOnTheDeadLetterTopic` covers the record that
  cannot be deserialized at all. It pins two things that failed in the live stack: the
  consumer needs `ErrorHandlingDeserializer`, and the worker needs a producer that can
  serialize both a `NotificationRequested` (listener failure) and a raw `byte[]` (poison
  record) -- see `KafkaProducerConfig`.
- `NotificationRetryIT` covers the other half: a sender that throws deserializes fine and
  fails *in the listener*, so it walks the retry topics before the DLT, and it asserts the
  delivery record the caller can then read. Two traps live in it. The retry topics are named
  after the backoff **delay**, not an attempt index (`notification.requested-retry-1000` then
  `-retry-2000`, for 1s then 2s), so changing `@RetryableTopic`'s backoff renames them. And
  they still have to be listed in `@EmbeddedKafka`: `consumeFromEmbeddedTopics` validates
  against that list rather than against the broker, and rejects a topic that does exist with
  "not in embedded topic list". It also pins the status walk the caller sees: RETRYING while
  a retry topic holds a try, then DEAD_LETTERED from the `@DltHandler`, with three FAILED
  attempt rows and a DEAD_LETTERED marker row, and the SMS `failed`/`dead_lettered` counters
  moving by 3 and 1 (asserted as deltas, so they hold whatever the context has seen before).
- `NotificationReadIT` seeds rows through the repositories, not the broker, so each gets the
  status and `updated_at` the assertion needs, then drives the list, filters, paging, size
  cap, stats window and the Prometheus endpoint's 401/403/200 through MockMvc against real
  MySQL. `DeliveryRecorderTest` covers the state transitions with Mockito, and
  `DeliveryStatusTest` pins every status and channel name to the varchar(16) columns.
- `EmailNotificationSenderTest` runs against GreenMail, an SMTP server inside the test JVM,
  so the one sender that speaks a real protocol is covered by a plain unit test with no
  container. `NotificationDispatchIT` uses the same server on GreenMail's conventional port
  3025, bound with `spring.mail.host=127.0.0.1` rather than localhost, which can resolve to
  ::1 first and refuse the connection.
- The delivery guarantees of ADR 0006 are proved in halves, because there is no harness that
  runs both deployables together and one would need a new module. `NotificationIngressIT`
  pins what reaches the topic: `retryAfterAnAckedButTimedOutPublishCarriesTheSameEventId`
  spies the publisher so the first send lands and then reports a timeout, and asserts the
  5xx, the retry's 202 `duplicate=false` under `EventIds.forIdempotencyKey`, and two records
  carrying that one id; `aRedisFailureAfterTheAckDoesNotSwallowTheRetry` fails
  `recordPublished` once and asserts both calls are accepted, the retry is republished rather
  than swallowed, and the next replay reads `duplicate=true`. The spies are
  `@MockitoSpyBean`, real unless stubbed and reset after each test. Every test there drains
  the records it produced from the shared consumer, or the next one's count is off.
  `NotificationDispatchIT.absorbsARepublishOfAnEventIdTheApiCouldNotConfirm` feeds the worker
  that shape -- two records, one event id, different `requestedAt` -- and holds for 2s that
  there is one row (keeping the first `requestedAt`), one attempt and one SMTP message.
  `NotificationIngressServiceTest` covers each branch with mocks, `EventIdsTest` pins the
  derivation against RFC 9562's vector and a golden value with a non-ASCII key, and
  `KafkaProducerConfigTest` pins that no property can weaken `acks=all` or idempotence.
- Keep the surefire/failsafe `-Dapi.version=1.44` argLine in the root pom. docker-java
  defaults to a Docker API version below the minimum Docker Engine 29+ enforces, and the
  failure is the misleading "Could not find a valid Docker environment".
- The meters are pinned where they are defined, without a context. `IngressMetricsTest` and
  `DeliveryMetricsTest` assert every outcome series exists at zero before the first request
  or event, since a series that appears only on its first increment is invisible to `rate()`
  and to an alert until then; `DeliveryMetricsTest` renders a `PrometheusMeterRegistry` and
  asserts `notification_dispatch_duration_seconds_bucket`, which the p95 panel and
  `NotificationDispatchSlow` need. `NotificationIngressServiceTest` asserts what each branch
  counts, including nothing for a Redis failure before the publish, and
  `NotificationIngressIT.prometheusTakesTheReadScope` the api endpoint's 401/403/200.
- The alert rules have promtool unit tests in `observability/prometheus/alerts.test.yml`:
  the dead-letter, target-down, failure-ratio and consumer-lag alerts, each silent before its
  condition and its `for`, firing after, with labels and annotations. CI's `observability`
  job runs `check config --syntax-only` (the scrape token file only exists inside compose),
  `check rules` and `test rules` in the image compose pins, so keep the two tags in step.
  Locally, from PowerShell, where Git Bash would rewrite the container path:
  `docker run --rm -v "${PWD}\observability\prometheus:/etc/prometheus:ro" --entrypoint promtool prom/prometheus:v3.15.1 test rules /etc/prometheus/alerts.test.yml`.
