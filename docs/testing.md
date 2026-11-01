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
- Keep the surefire/failsafe `-Dapi.version=1.44` argLine in the root pom. docker-java
  defaults to a Docker API version below the minimum Docker Engine 29+ enforces, and the
  failure is the misleading "Could not find a valid Docker environment".
