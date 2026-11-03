# 7. Scraping inside compose: a refreshed dev token, not a management port

- **Status:** Accepted. Amends the compose consequence of [0005](0005-delivery-states-and-the-read-api.md).
- **Date decided:** 2026-11-03
- **Date recorded:** 2026-11-03, with the change

## Context

0005 put the worker's `/actuator/prometheus` behind the `notifications.read` scope and, because
dev tokens last an hour, left compose without a Prometheus: "a scraper with a token it has to
keep refreshing is worse than none". The api exported no metrics at all.

The observability pack wants `docker compose up --build` to bring up Prometheus and Grafana
over both apps, without loosening anything outside compose and without changing an existing
route.

The first proposal was a management port: `management.server.port` set in compose only, not
published to the host, reachable on the compose network alone. Read against the Boot 4.1.1
jars before building it, it does not do what it would need to:

1. **It moves every actuator endpoint, not just one.** `WebMvcEndpointManagementContextConfiguration`
   is registered in the main context when the management port is the server port, and only
   in the child context when it differs. In compose, `/actuator/health` and the worker's
   scoped `/actuator/metrics` and `/actuator/prometheus` would leave :8080 and :8081: an
   existing route changed, and health off the port anything probing the app uses.
2. **The child context reuses the main security chain.** `ServletManagementChildContextConfiguration`
   (in `spring-boot-servlet`) registers the parent's `springSecurityFilterChain` in the
   child, so the main port's rules, the read scope on `/actuator/prometheus` included, apply
   on the management port too. Opening it there needs a port-aware rule in `SecurityConfig`:
   production code that knows about compose.

## Decision

**Compose keeps a dev token fresh for Prometheus, and scrapes the existing scoped endpoints
with it.**

- **A `scrape-token` container** (`curlimages/curl`, pinned) runs `observability/scrape-token.sh`:
  it POSTs the documented dev credential to the api's `/api/auth/token`, writes the token
  beside `/token/scrape` in a named volume and renames it into place, and refreshes it every
  30 minutes, retrying every 5 s until the first success. It deletes any token an earlier run
  left in the volume, and never prints one. Its healthcheck passes once the file has content,
  and Prometheus waits for that.
- **Prometheus scrapes `np-api:8080` and `np-worker:8081` on `/actuator/prometheus`** with
  `authorization.credentials_file: /token/scrape`. Prometheus re-reads that file on every
  scrape, so a refresh needs no reload. Checked live: a garbage token turned both targets
  DOWN with a 401 within one scrape, and restarting the sidecar brought both back UP.
- **The api exposes `metrics` and `prometheus` under the read scope**, the worker's rule. Both
  routes were 404 before; no existing route, rule or contract changed.
- **New meters, fixed cardinality**, registered up front like `DeliveryMetrics`:
  `notification.ingress{outcome=accepted|duplicate|publish_failed}` and the
  `notification.publish.duration` timer in the api; `notification.dispatch.duration` gains
  histogram buckets, so a p95 can be aggregated in Prometheus. Consumer lag needs no code:
  Boot's `KafkaMetricsAutoConfiguration` binds the worker's consumers to Micrometer.
- **Grafana and alert rules ride along**, both compose-only: a provisioned datasource and
  dashboard with anonymous viewing, and rules in `observability/prometheus/alerts.yml` with
  promtool unit tests that CI runs.

Nothing outside compose changes: the apps' default configuration opens no port and no route
without the read scope.

### Why 0005's objection no longer holds

0005's objection was to a scraper someone has to feed by hand: a token pasted into a config
that goes stale an hour later and leaves a dashboard of gaps. Here the refresh is a container
compose starts and health-checks, and a stale token is loud rather than silent: both targets
go DOWN and `NotificationTargetDown` fires a minute later. It remains a dev arrangement, which
is why the decision stops at compose.

## Consequences

- **Scraping is solved inside compose only.** A real deployment needs a scrape credential from
  an identity provider, for which Prometheus has a native `oauth2:` client-credentials block.
  That is still the API Gateway project's job; the known gap is reworded, not closed.
- **The scrape token also carries the write scope.** The dev endpoint mints one client
  credential with both scopes (the existing "scopes separate the routes, not the callers"
  gap), so whatever holds the token could POST notifications. Prometheus does not; a
  read-only scrape credential needs the IdP too.
- **The token sits in a named volume**, `np_scrape_token`, readable by any container that
  mounts it, and is never more than an hour from expiry.
- **The dev credential now appears in `compose.yaml`** as well as `application.properties` and
  `http-client.env.json`. It was already public and is still not a secret.
- **A broken refresh surfaces as both targets DOWN** within an hour of the last good token,
  and as the sidecar's "retrying" log line.
- **Two new host ports, 9090 and 3000, and no new app port.** The ports the apps publish are
  the ones they published before.

## Alternatives considered

- **A management port in compose** (the first proposal). Rejected for the two findings above:
  it takes health and the worker's scoped reads off the main ports, and the main chain applies
  on it anyway.
- **A second connector on the same context, with a port-aware security rule.** Keeps the main
  ports as they are and opens `/actuator/prometheus` on an internal port only. Rejected: the
  open rule lives in `SecurityConfig`, production code, one property away from opening the
  endpoint wherever that property is set.
- **`/actuator/prometheus` open behind a compose-only profile.** The simplest scrape. Rejected
  for the reason 0005 gave: the metrics are a read, and the compose api's 8080 is published
  to the host, so "compose only" would still mean anything that can reach the machine.
- **A long token TTL in compose** (`NOTIFICATION_AUTH_TOKEN_TTL` of days). One container fewer.
  Rejected: it lengthens every token the compose api issues, the ones a developer pastes into
  the HTTP client included, and the scrape still dies when it expires, only later and less
  visibly.
- **A read-only token signed outside the api**, with the dev secret and a script. Narrower
  than the sidecar's token. Rejected: it puts the signing secret into tooling and hand-rolls a
  JWT that can drift from what `TokenService` issues, and it still expires.
- **Prometheus's native `oauth2:` client credentials against the dev endpoint.** The right
  shape for production. Not possible here: the dev endpoint is not an RFC 6749 token
  endpoint. It takes JSON `clientId`/`clientSecret` rather than a form-encoded
  `grant_type=client_credentials` and answers `accessToken` rather than `access_token`.
  Making it one is building the IdP.
- **Push instead of pull**, through OTLP to a collector or a Pushgateway. No inbound auth to
  solve. Rejected: it adds a runtime dependency to both apps, where the Prometheus registry in
  the api is the only new one this change allows.
