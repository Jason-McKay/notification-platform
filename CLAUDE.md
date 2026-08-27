# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

**Notification Platform** — an event-driven notification system: an HTTP ingress accepts
requests, publishes them to Kafka, and a worker consumes, dispatches, and records them.
Spring Boot 4.1 / Java 21, Maven multi-module.

It is deliberately a *platform*, not a service: it ships **two deployables**, which is the
point of the project (project #2 on the portfolio roadmap — microservices + event-driven).

Related repos: `C:\Users\jason\Projects\message-inspector\` (JMS/Artemis tooling, the
conventions here mostly come from it), `C:\Users\jason\Projects\infrastructure\` (shared
dev stack — **not** used by this repo, see Gotchas).

## Modules

| Module | Packaging | Role |
|---|---|---|
| `notification-common` | plain jar | The wire contract: event records shared by both apps. No Spring. |
| `notification-api` | Boot app, :8080 | Write-only ingress. Validates, dedupes, publishes. Owns no tables. |
| `notification-worker` | Boot app, :8081 | Consumes, dispatches, owns the schema and the status read. |

**`notification-common` must never apply `spring-boot-maven-plugin`.** Repackage rewrites
the jar into `BOOT-INF/classes` and the other modules stop being able to consume it.

**The worker owns the tables, so `GET /api/notifications/{id}` lives there too**, not on
the api. Two services sharing one schema is the anti-pattern this project exists to
demonstrate avoiding; the cost is that clients POST to :8080 and GET from :8081, which the
roadmap's later API Gateway project is the natural front for.

## Commands

Maven wrapper (`./mvnw` in Bash, `.\mvnw.cmd` in PowerShell), run from the repo root:

```bash
./mvnw clean install                      # all modules; common must be installed for the apps to resolve it
./mvnw test                               # unit tests only (surefire), no Docker needed
./mvnw verify                             # unit + *IT tests (failsafe, Testcontainers -> needs Docker)
./mvnw -pl notification-api test          # single module
./mvnw -pl notification-api spring-boot:run
```

## Conventions

Packages under `com.notification.platform.{common,api,worker}`, named by the role they
play. Layering is defined per module in `docs/adr/` — do not import another project's
package list wholesale; if a module has no persistence, it gets no `repository/`.

Commits: conventional style, short subject line.

## Gotchas

- **Port and container collisions.** The shared `infrastructure` stack holds `dev-redis`
  :6379, `dev-mysql` :3306 and `dev-artemis`; this repo's `compose.yaml` is self-contained
  and uses `np-` container names on shifted host ports so both can run at once. The api on
  :8080 still collides with message-inspector — run one at a time.
- **Do not remove the surefire/failsafe `-Dapi.version=1.44` argLine** in the root pom.
  docker-java's default Docker API version is below the minimum Docker Engine 29+ enforces,
  and the failure is the misleading "Could not find a valid Docker environment".
- **Boot 4.1 is a young line.** Expect rough edges and bump patches early.
