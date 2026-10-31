# Gotchas

Moved out of `CLAUDE.md` on 2026-10-31, word for word, so it no longer loads into every
session. `CLAUDE.md` keeps each rule as a line or two and points here for the reason. Where the
text below says "this file", it means `CLAUDE.md` as it stood when the passage was written.

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
  19092) so both can run at once. Mailpit is the exception, on its own defaults: SMTP 1025
  and the inbox at http://localhost:8025. The api on :8080 still collides with message-inspector —
  run one at a time.
- **`spring-boot-starter-mail-test` ships no mail server.** Despite the name it is only
  `spring-boot-starter-mail` plus `spring-boot-starter-test`, so the SMTP server the tests
  need is GreenMail, pinned in the root pom (the Boot BOM does not manage it). It must be
  the 2.x line: 1.x is built against `javax.mail` and Boot 4 is jakarta throughout.
- **Template placeholders are `{{name}}`, not `${name}`.** The templates live in
  `application.properties`, and Spring resolves `${...}` itself while binding: a variable
  spelled that way is an unresolvable placeholder and the worker will not start.
- **A new `DeliveryStatus` must fit `varchar(16)`, on both tables.** Hibernate's
  `ddl-auto=validate` checks neither the enum's constants nor the column length, so a longer
  name starts fine and fails as "Data too long" on its first write; for a parking state that
  write is inside the DLT handler. `DeliveryStatusTest` fails first. Widen both `status`
  columns in a migration before adding one.
- **Boot 4.1 is a young line.** Expect rough edges and bump patches early.
