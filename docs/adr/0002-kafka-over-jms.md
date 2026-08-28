# 2. Kafka, not JMS, for the event backbone

- **Status:** Accepted
- **Date decided:** 2026-08-27
- **Date recorded:** 2026-08-28, with the initial scaffold

## Context

The obvious cheap choice was ActiveMQ Artemis over JMS: the shared dev stack in
`../infrastructure` already runs a broker, and message-inspector is built on it, so another
JMS application would have cost nothing to stand up.

The properties this platform wants, though, are the ones a log-structured broker gives:
replay of events a worker missed while it was down, ordering per recipient rather than
globally, and consumers that scale out by partition.

## Decision

Kafka, in KRaft mode, with:

- **the recipient id as the message key**, so one recipient's notifications land on one
  partition and stay ordered relative to each other while the topic as a whole scales;
- **@RetryableTopic** for retries, so a failing event moves to a retry topic instead of
  blocking its partition, and lands on `notification.requested-dlt` when attempts run out;
- **topics declared as beans**, not left to broker auto-creation, which would give one
  partition and no say in it.

## Consequences

- The platform runs its own broker in `compose.yaml`; it does not use the shared dev
  Artemis. Two brokers now exist on this machine, for different projects.
- Retry topics are visible infrastructure: `notification.requested-retry-*` and `-dlt` show
  up in any topic listing. That is a feature when debugging, and a surprise if you expected
  a JMS-style invisible redelivery counter.
- **Consumer offsets are the durability story, not queue depth.** A worker restarted with a
  new group id re-reads history; the same group id resumes. `auto-offset-reset=earliest` is
  set so a first-ever start does not silently skip events the api already published.
- Nothing in this repo demonstrates JMS. That overlap with message-inspector was the reason
  to choose differently, not a gap.
- Kafka under Testcontainers did not start on the Windows dev machine (see the note in
  CLAUDE.md), which is a cost this choice carries locally.

## Alternatives considered

- **Artemis/JMS on the shared stack.** Rejected: cheapest to build, but it duplicates what
  message-inspector already shows, and JMS gives no replay.
- **Kafka plus a JMS ingress path.** Rejected as scaffold scope: a protocol bridge is worth
  building when something actually needs to speak JMS to this platform.
