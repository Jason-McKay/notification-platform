# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

**This file holds the rules; the reasons are in `docs/`.** Until 2026-10-31 the incident or
argument behind each rule was in here too, loaded into every session; it moved word for word
into the files below, and here each rule is a line or two. **Read a rule's reason before
changing or deleting it.** New reasoning goes in the matching `docs/` file, not here.
Decisions are in `docs/adr/`, which the split did not touch.

| Topic | Reasons in |
|---|---|
| What each test covers, embedded Kafka, the poison record, retry-topic names, GreenMail, the Docker API pin | `docs/testing.md` |
| Boot 4 starters, Jackson 3, Testcontainers 2, pom comments, Kafka listeners, image builds, ports, templates, `varchar(16)` | `docs/gotchas.md` |
| Each known gap and why it is left open | `docs/known-gaps.md` |

## What this is

**Notification Platform** — an event-driven notification system: an HTTP ingress accepts
requests, publishes them to Kafka, and a worker consumes, dispatches and records them.
Spring Boot 4.1 / Java 21, Maven multi-module.

It is deliberately a *platform*, not a service: it ships **two deployables**, which is the
point of the project (portfolio project #2 — microservices + event-driven).

Roadmap: a local planning doc kept outside the repo — current status, the
ordered next actions, and what is deliberately deferred. **Read it before starting
non-trivial work.** It is kept out of this repo on purpose: the repo is public.

Related repos: `message-inspector` (JMS/Artemis tooling; most of
the conventions here come from it), `life-api` (the other Boot 4
project), `infrastructure` (shared dev stack — **not** used by
this repo, see Gotchas).

## Modules

| Module | Packaging | Role |
|---|---|---|
| `notification-common` | plain jar | The wire contract: event records shared by both apps. No dependencies, not even Spring. |
| `notification-api` | Boot app, :8080 | Write-only ingress. Validates, dedupes, publishes. Owns no tables. |
| `notification-worker` | Boot app, :8081 | Consumes, dispatches, owns the schema, the delivery reads and the delivery metrics. |

**`notification-common` must never apply `spring-boot-maven-plugin`.** Repackage rewrites
the jar into `BOOT-INF/classes` and the other modules stop being able to consume it.

**The worker owns the tables, so the delivery reads live there**, not on the api — see
[ADR 0003](docs/adr/0003-worker-owns-the-schema.md). Clients therefore POST to :8080 and
GET from :8081: `GET /api/notifications` (a filtered page, newest first),
`/api/notifications/stats` (counts over the last 24h) and `/api/notifications/{eventId}`
(one delivery and its attempts), all in `NotificationStatusController` and all under the
read scope, as are `/actuator/metrics/**` and `/actuator/prometheus`. None returns the
payload. States, contract and metric names: [ADR 0005](docs/adr/0005-delivery-states-and-the-read-api.md).

## Commands

Maven wrapper (`./mvnw` in Bash, `.\mvnw.cmd` in PowerShell), from the repo root:

```bash
./mvnw clean install                        # all modules
./mvnw test                                 # unit only (surefire), no Docker
./mvnw verify                               # unit + *IT (failsafe, Testcontainers -> needs Docker)
./mvnw -pl notification-api -am test        # one module, building what it depends on
./mvnw -pl notification-api spring-boot:run
docker compose up --build                   # whole stack: kafka, redis, mysql, both apps
```

`-pl <module>` **without `-am` fails** unless the root pom and `notification-common` are
already installed: a module's parent is this repo's aggregator. Run `./mvnw install` once,
or always pass `-am`.

`http/notification-platform.http` is a runnable end-to-end scenario (token, 401, accept,
replay, status read, 404, a dead-lettered event, the list, stats and metrics). Select the `dev` environment from `http/http-client.env.json`
first. Works in IntelliJ's HTTP Client and VS Code's REST Client.

CI (`.github/workflows/ci.yml`) runs `./mvnw -B verify` on push/PR — `verify`, not `test`,
because the ITs are what cover the wiring between the two deployables.

### Testing notes

Rules, with the reasons in `docs/testing.md`:

- **`./mvnw test` does not run the `*IT` classes.** That is deliberate: `test` stays fast
  and Docker-free. `./mvnw verify` runs them under failsafe.
- **Kafka in the ITs is spring-kafka's `@EmbeddedKafka`, not Testcontainers.** Both
  Testcontainers Kafka images set `listeners` to `0.0.0.0` and fail to start here (see
  *Gotchas*). MySQL and Redis containers work fine and are used.
- **Which test covers what:** `NotificationDispatchIT` (event on the broker → row in MySQL,
  plus the poison record), `NotificationIngressIT` (an accepted request reaches the topic),
  `NotificationRetryIT` (retry topics, the DLT, the status walk and counters),
  `NotificationReadIT` (the read api against real MySQL), `DeliveryRecorderTest`
  (transitions), `DeliveryStatusTest` (names fit the columns), `EmailNotificationSenderTest`
  (GreenMail inside the test JVM).
- **The consumer needs `ErrorHandlingDeserializer`, and the worker needs a producer that
  serializes both a `NotificationRequested` and a raw `byte[]`** (`KafkaProducerConfig`).
  `poisonRecordEndsUpOnTheDeadLetterTopic` pins both.
- **Retry topics are named after the backoff delay** (`notification.requested-retry-1000`,
  `-retry-2000`), so changing `@RetryableTopic`'s backoff renames them. They must still be
  listed in `@EmbeddedKafka`, or `consumeFromEmbeddedTopics` rejects them.
- **Bind GreenMail with `spring.mail.host=127.0.0.1`, not localhost**, which can resolve to
  ::1 first and refuse the connection.
- **Keep the surefire/failsafe `-Dapi.version=1.44` argLine in the root pom.** Without it
  Docker Engine 29+ fails with the misleading "Could not find a valid Docker environment".

## Conventions

- Packages under `com.notification.platform.{common,api,worker}`, **named by the role they
  play**. A module gets no `repository/` if it persists nothing — the api has none.
- Java is 2-space indented, matching `message-inspector` and `life-api`.
- Commits: conventional style, short subject.
- Non-obvious decisions go in `docs/adr/` (Nygard-style, numbered, append-only).

## Gotchas

Rules, with the reasons in `docs/gotchas.md`:

- **Use the starters** (`spring-boot-starter-kafka`, `spring-boot-starter-flyway`). Boot 4
  split autoconfiguration per technology, so bare `spring-kafka` or `flyway-core` brings no
  Boot integration — and Flyway's migrations then silently never run.
- **Boot's autoconfigured `KafkaTemplate` is `<Object, Object>`.** The api declares its own
  typed template in `KafkaProducerConfig` rather than widening the publisher to `Object`.
- **Test slices moved:** `@WebMvcTest` is in `spring-boot-webmvc-test`. `TestRestTemplate`
  appears to be gone, so ITs use the JDK `HttpClient` with `@LocalServerPort`.
- **Boot 4 ships Jackson 3** (`tools.jackson`): `JacksonJsonSerializer`/
  `JacksonJsonDeserializer`, not the Jackson 2 `JsonSerializer`.
- **Testcontainers 2.x** artifacts are `testcontainers-*`, and `MySQLContainer` is no longer
  generic: `new MySQLContainer("mysql:8")`.
- **XML comments cannot contain `--`.** It is a fatal "Non-parseable POM" pointing at the
  comment.
- **Kafka's container listeners must be `://port`, not `://0.0.0.0:port`.**
- **A module image build needs every module pom**, so both Dockerfiles copy all three poms
  before the sources.
- **Port and container collisions.** `compose.yaml` uses `np-` container names on shifted host
  ports (Redis 6380, MySQL 3307, Kafka 19092) so it runs beside `infrastructure`; Mailpit is on
  its defaults (SMTP 1025, inbox http://localhost:8025). The api on :8080 still collides with
  message-inspector — run one at a time.
- **`spring-boot-starter-mail-test` ships no mail server.** The tests use GreenMail 2.x,
  pinned in the root pom; 1.x is `javax.mail`.
- **Template placeholders are `{{name}}`, not `${name}`**, or the worker will not start.
- **A new `DeliveryStatus` must fit `varchar(16)`, on both tables.** Widen both `status`
  columns in a migration before adding one.
- **Boot 4.1 is a young line.** Expect rough edges and bump patches early.

## Known gaps

Each is a line here; why it is open, and what closing it would take, is in `docs/known-gaps.md`.

- The dev token endpoint (`POST /api/auth/token`) is a stand-in for an identity provider: one
  client credential, HS256, `notification.jwt.secret` shared by both apps.
- **Scopes separate the routes, not the callers**: one client credential is minted both.
- EMAIL is real (SMTP, plain text only); SMS and PUSH are `LoggingNotificationSender`
  stand-ins, and a provider is a bean in `SenderConfig`.
- Nothing validates `destination`, and the api accepts any `templateKey`.
- Templates are configuration (`notification.templates.*`), so changing copy is a redeploy.
- A poison record reaches the DLT but leaves no row; the status read answers 404 for it.
- The list pages by offset over `updated_at`, so a client paging during deliveries can see a
  row twice or miss one (ADR 0005).
- A `@DltHandler` that throws sends the record back to the DLT. Left alone.
- Compose runs no Prometheus server: scraping needs a bearer token and dev tokens last an hour.
- Rows parked before RETRYING/DEAD_LETTERED still read `FAILED`, and the new states are
  one-way: a rollback maps them back by hand first (the SQL is in ADR 0005).
- Only the main topic and the DLT are declared as beans; retry topics are created on demand.
- A crash between the send and the SENT record sends again (ADR 0004). At-least-once,
  deliberately.
- **`notification.requested` is a `NewTopic` bean in both apps**, at 3 partitions (the worker
  ITs' `@EmbeddedKafka` matches). **The DLT is still declared only by the api** — a residual
  race.
- OWASP Dependency-Check is not wired in; add it with the `dep-audit` skill (needs the
  `NVD_API_KEY` secret) once the dependency set settles.
- **The remote is public**: `origin` is github.com/Jason-McKay/notification-platform.
  Pushing still needs an explicit go-ahead, and whatever is committed is published, so no
  secrets or absolute local paths. The credentials in `application.properties` are dev-only
  stand-ins and already public.
