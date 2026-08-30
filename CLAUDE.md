# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

**Notification Platform** — an event-driven notification system: an HTTP ingress accepts
requests, publishes them to Kafka, and a worker consumes, dispatches and records them.
Spring Boot 4.1 / Java 21, Maven multi-module.

It is deliberately a *platform*, not a service: it ships **two deployables**, which is the
point of the project (portfolio project #2 — microservices + event-driven).

Roadmap: `C:\Users\jason\Claude\plans\notification-platform-plan.md` — current status, the
ordered next actions, and what is deliberately deferred. **Read it before starting
non-trivial work.** It is kept out of this repo on purpose: the repo is headed for a public
remote.

Related repos: `C:\Users\jason\Projects\message-inspector\` (JMS/Artemis tooling; most of
the conventions here come from it), `C:\Users\jason\Projects\life-api\` (the other Boot 4
project), `C:\Users\jason\Projects\infrastructure\` (shared dev stack — **not** used by
this repo, see Gotchas).

## Modules

| Module | Packaging | Role |
|---|---|---|
| `notification-common` | plain jar | The wire contract: event records shared by both apps. No dependencies, not even Spring. |
| `notification-api` | Boot app, :8080 | Write-only ingress. Validates, dedupes, publishes. Owns no tables. |
| `notification-worker` | Boot app, :8081 | Consumes, dispatches, owns the schema and the status read. |

**`notification-common` must never apply `spring-boot-maven-plugin`.** Repackage rewrites
the jar into `BOOT-INF/classes` and the other modules stop being able to consume it.

**The worker owns the tables, so `GET /api/notifications/{eventId}` lives there**, not on
the api — see [ADR 0003](docs/adr/0003-worker-owns-the-schema.md). Clients therefore POST
to :8080 and GET from :8081.

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
replay, status read, 404). Select the `dev` environment from `http/http-client.env.json`
first. Works in IntelliJ's HTTP Client and VS Code's REST Client.

CI (`.github/workflows/ci.yml`) runs `./mvnw -B verify` on push/PR — `verify`, not `test`,
because the ITs are what cover the wiring between the two deployables.

### Testing notes

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
  "not in embedded topic list".
- Keep the surefire/failsafe `-Dapi.version=1.44` argLine in the root pom. docker-java
  defaults to a Docker API version below the minimum Docker Engine 29+ enforces, and the
  failure is the misleading "Could not find a valid Docker environment".

## Conventions

- Packages under `com.notification.platform.{common,api,worker}`, **named by the role they
  play**. A module gets no `repository/` if it persists nothing — the api has none.
- Java is 2-space indented, matching `message-inspector` and `life-api`.
- Commits: conventional style, short subject.
- Non-obvious decisions go in `docs/adr/` (Nygard-style, numbered, append-only).

## Gotchas

Most of these cost real time on the way in.

- **Boot 4 split autoconfiguration into per-technology modules.** Bare
  `org.springframework.kafka:spring-kafka` gives you the library and *no* Boot integration:
  no `KafkaTemplate`, no listener container factory, no `spring.kafka.*` binding. Same for
  `flyway-core`, where the symptom is worse — migrations silently never run and Hibernate
  then fails validating tables that were never created. **Use the starters**
  (`spring-boot-starter-kafka`, `spring-boot-starter-flyway`).
- **Boot's autoconfigured `KafkaTemplate` is `<Object, Object>`** and does not satisfy an
  injection point typed to the event; startup fails with "no qualifying bean". The api
  declares its own typed template in `KafkaProducerConfig` rather than widening the
  publisher to `Object`.
- **Test slices moved too**: `@WebMvcTest` is in `spring-boot-webmvc-test`, not
  `spring-boot-starter-test`. `TestRestTemplate` appears to be gone in Boot 4 —
  `NotificationIngressIT` uses the JDK `HttpClient` with `@LocalServerPort`
  (`org.springframework.boot.test.web.server.LocalServerPort`).
- **Boot 4 ships Jackson 3** (`tools.jackson`, not `com.fasterxml`). In spring-kafka that
  means `JacksonJsonSerializer`/`JacksonJsonDeserializer`; the unprefixed `JsonSerializer`
  is the Jackson 2 one and will not work here.
- **Testcontainers 2.x renamed every module artifact** to `testcontainers-*`
  (`testcontainers-mysql`, `testcontainers-junit-jupiter`), and `MySQLContainer` is no
  longer generic — `new MySQLContainer("mysql:8")`, not `new MySQLContainer<>(...)`.
- **XML comments cannot contain `--`.** An em-dash-style `--` inside a pom comment is a
  fatal "Non-parseable POM", and the message points at the comment, not the pom's real
  shape.
- **Kafka's container listeners must be `://port`, not `://0.0.0.0:port`.** The image runs
  `kafka-storage format` before the broker, and that step validates 0.0.0.0 as an advertised
  address: "advertised.listeners cannot use the nonroutable meta-address". This is also why
  Testcontainers Kafka fails here, since it sets 0.0.0.0 itself.
- **A module image build needs every module pom**, not just its own: the aggregator refuses
  to load if any directory in `<modules>` is missing, so both Dockerfiles copy all three
  poms before the sources they actually compile.
- **Port and container collisions.** The shared `infrastructure` stack holds `dev-redis`
  :6379, `dev-mysql` :3306 and `dev-artemis`; this repo's `compose.yaml` is self-contained
  and uses `np-` container names on shifted host ports (Redis 6380, MySQL 3307, Kafka
  19092) so both can run at once. The api on :8080 still collides with message-inspector —
  run one at a time.
- **Boot 4.1 is a young line.** Expect rough edges and bump patches early.

## Known gaps

- The dev token endpoint (`POST /api/auth/token`) is a stand-in for an identity provider:
  one configured client credential, HS256, no user store. Both applications share
  `notification.jwt.secret`, which is what symmetric signing costs. An IdP with JWKS is the
  API Gateway project's job.
- **Scopes separate the routes, not the callers.** `notifications.write` guards the api's
  POST and `notifications.read` the worker's GET (`Scopes` in `notification-common`, so the
  minting side and the two verifying sides cannot drift), but there is one client credential
  and it is minted both. Different clients getting different scopes needs the IdP above.
- Senders are `LoggingNotificationSender` stand-ins, one per channel. Nothing is actually
  delivered anywhere yet, and `templateKey` is only logged — there is no rendering.
- There is no RETRYING state. A failed attempt is recorded as soon as it happens, so an
  event still working its way through the retry topics reads `FAILED` between tries and only
  settles when it is SENT or reaches the DLT.
- The retry topics are created by Spring Kafka on demand and named after the backoff delay
  (`notification.requested-retry-1000`, `-retry-2000`); only the main topic and the DLT are
  declared as beans.
- `DispatchService` calls the sender inside the transaction. Fine for a logging sender;
  revisit with an ADR when a real provider call arrives. It is also why `DeliveryRecorder`
  exists and why the listener, not the dispatcher, calls it: a failure recorded inside that
  transaction rolls back with it.
- OWASP Dependency-Check is not wired in; add it with the `dep-audit` skill (needs the
  `NVD_API_KEY` secret) once the dependency set settles.
- No git remote. Portfolio repos are public, so pushing needs an explicit go-ahead and a
  look at the dev credentials in `application.properties` first.
