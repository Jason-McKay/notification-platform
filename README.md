# Notification Platform

An event-driven notification system: an HTTP ingress accepts requests, publishes them to
Kafka, and a worker consumes, dispatches and records them.

Spring Boot 4.1 / Java 21 / Kafka / Redis / MySQL, as a Maven multi-module build. It is
deliberately a *platform* rather than a service: two deployables that scale and fail for
different reasons, which is the point of the exercise.

## Modules

| Module | What it is |
|---|---|
| `notification-common` | The wire contract: event records, channel enum, topic names. No dependencies, not even Spring. |
| `notification-api` | Write-only ingress on :8080. Validates, dedupes on `Idempotency-Key` in Redis, publishes. Owns no tables. |
| `notification-worker` | Consumer on :8081. Dispatches through a channel abstraction, owns the schema and serves the delivery-status read. |

## Running it

```bash
docker compose up --build
```

Brings up Kafka, Redis, MySQL and both applications. Then, from
`http/notification-platform.http` (select the `dev` environment), or by hand:

```bash
TOKEN=$(curl -s -X POST localhost:8080/api/auth/token -H 'Content-Type: application/json' \
  -d '{"clientId":"notification-client","clientSecret":"dev-secret"}' | jq -r .accessToken)

curl -s -X POST localhost:8080/api/notifications \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo-1' \
  -d '{"recipientId":"user-42","channel":"EMAIL","templateKey":"order-shipped","payload":{"orderId":"A-1001"}}'

# the id comes back from the POST; the status read is on the worker, not the api
curl -s localhost:8081/api/notifications/<eventId> -H "Authorization: Bearer $TOKEN"
```

Swagger UI (dev profile only): http://localhost:8080/api/swagger-ui.html

## Building

```bash
./mvnw clean install     # all three modules
./mvnw test              # unit tests, no Docker needed
./mvnw verify            # unit + *IT integration tests (needs Docker)
```

## Design decisions

The non-obvious ones are recorded in [`docs/adr/`](docs/adr/README.md):

- [0001](docs/adr/0001-three-modules-one-contract.md) — three modules: one contract jar, two deployables
- [0002](docs/adr/0002-kafka-over-jms.md) — Kafka, not JMS, for the event backbone
- [0003](docs/adr/0003-worker-owns-the-schema.md) — the worker owns the schema, and therefore the status read

`CLAUDE.md` carries the working notes: commands, conventions, and the traps this stack has
already sprung.
