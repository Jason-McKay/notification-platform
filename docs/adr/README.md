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
| [0004](0004-the-provider-call-leaves-the-transaction.md) | The provider call leaves the transaction | Accepted |
| [0005](0005-delivery-states-and-the-read-api.md) | Delivery states, the read api, and what the metrics expose | Accepted |
| [0006](0006-delivery-guarantees.md) | Delivery guarantees: a derived event id, and keys recorded after the ack | Accepted |
| [0007](0007-scraping-inside-compose.md) | Scraping inside compose: a refreshed dev token, not a management port | Accepted |

The first three were written as the scaffold was laid down on 2026-08-28, recording
decisions taken the evening before. 0004 came later, when the first real provider forced a
question the walking skeleton had been able to defer. 0005 was written with the list, stats
and metrics endpoints, when a dashboard needed the status model to tell retrying from
parked. 0006 came when two holes in the idempotency story, a publish that timed out after
the broker took it and a Redis outage on the failure path, needed the event id to follow
from the key rather than from a claim. 0007 was written with the observability pack, when
compose needed a Prometheus after all and the management port it was first going to use
turned out to move every actuator endpoint.
