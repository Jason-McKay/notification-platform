# 3. The worker owns the schema, and therefore the status read

- **Status:** Accepted
- **Date decided:** 2026-08-27
- **Date recorded:** 2026-08-28, with the initial scaffold

## Context

Delivery records have to live somewhere, and clients want to ask "did my notification go
out?". The tempting arrangement is for the api to answer that -- it is the service clients
already talk to -- by reading the same tables the worker writes.

That is two services against one schema, the specific failure this project exists to
demonstrate avoiding: the moment the api reads those tables, a migration shipped in the
worker can break a deployable that does not contain it.

## Decision

- The **worker owns the schema**: JPA entities, the datasource, and Flyway live in
  notification-worker and nowhere else. `spring.jpa.hibernate.ddl-auto=validate` keeps the
  mapping honest against the migrations instead of writing DDL of its own.
- The worker therefore serves the read: `GET /api/notifications/{eventId}` on :8081.
- The api owns no tables at all. It has no `repository/` package, because it has nothing to
  persist -- Redis holds idempotency keys and nothing else.
- The event id from the topic is the notification table's **primary key**, so an
  at-least-once redelivery collides with the row already there instead of sending twice.

## Consequences

- **Clients POST to :8080 and GET from :8081.** That is the visible cost, and it is the
  shape an API gateway is for -- the roadmap's gateway project is the natural front for
  both. Until then, two hosts.
- The api can be deployed, rolled back or scaled without regard to schema state.
- Delivery status is only as fresh as the worker's consumption. There is no path by which
  the api could report a status the worker has not written yet, which is the honest answer
  rather than an optimistic one.

## Alternatives considered

- **The api reads the worker's tables.** Rejected: convenient for a client, and the exact
  coupling this platform is shaped to avoid.
- **The worker publishes a NotificationDelivered event, the api projects it into Redis and
  serves the read.** The better answer if the split hosts become annoying before the
  gateway exists: one owner per store, and a read model the api controls. Deferred because
  it is a second event type and a projection to maintain, for a read path that currently
  has no consumer.
