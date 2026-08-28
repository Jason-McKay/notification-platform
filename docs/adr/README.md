# Architecture Decision Records

Short records of decisions that were **not obvious**, written so the reasoning survives the
people who made it. Each one states the context, the decision, and what it cost.

Format is Nygard-style, numbered, append-only: supersede a record rather than editing it
once it is accepted.

| # | Title | Status |
|---|---|---|
| [0001](0001-three-modules-one-contract.md) | Three modules: one contract jar, two deployables | Accepted |
| [0002](0002-kafka-over-jms.md) | Kafka, not JMS, for the event backbone | Accepted |
| [0003](0003-worker-owns-the-schema.md) | The worker owns the schema, and therefore the status read | Accepted |

All three were written as the scaffold was laid down on 2026-08-28, recording decisions
taken the evening before.
