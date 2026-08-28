# 1. Three modules: one contract jar, two deployables

- **Status:** Accepted
- **Date decided:** 2026-08-27
- **Date recorded:** 2026-08-28, with the initial scaffold

## Context

The project is the portfolio's microservices and event-driven piece, so more than one
deployable is the point rather than an accident: an HTTP ingress that must stay responsive
under a burst, and a worker whose speed is bounded by whatever it delivers through. They
scale for different reasons and fail for different reasons.

Both need to agree on the shape of the event that travels between them. Copying the record
into both modules would let the two copies drift; putting it in either application's jar
would make one deployable depend on the other's build.

## Decision

Three Maven modules under one aggregator:

- **notification-common** -- the wire contract only: the event records, the channel enum,
  the topic names. **No compile dependencies at all**, not even Spring. A contract module
  that drags framework configuration across the boundary has stopped being a contract.
- **notification-api** -- the ingress deployable.
- **notification-worker** -- the consumer deployable.

The Spring Boot Maven plugin is declared **only** in the two application modules.

## Consequences

- The contract is one file both sides compile against, so a change that breaks the other
  application breaks the build rather than production.
- **The repackage trap is one line away.** If the Boot plugin is ever added to
  notification-common, repackage rewrites its jar into BOOT-INF/classes and both
  applications stop being able to consume it. Its absence there is deliberate.
- Building one module needs the aggregator: `./mvnw -pl notification-api -am ...`, or
  `./mvnw install` once so the modules resolve from the local repository.
- A shared jar is still coupling. It is the smallest kind available -- compile-time, on a
  type both sides already have to agree about -- but a wire-format change means releasing
  both deployables together, and the Jackson round-trip test in notification-common exists
  to make that visible early.

## Alternatives considered

- **Two repositories with a duplicated event class.** Rejected: no compiler anywhere would
  notice the day they disagree.
- **A schema registry with generated classes (Avro/Protobuf).** The right answer when
  producer and consumer are released by different teams. Here it would add a registry to
  run and a code-generation step to explain, for one record.
