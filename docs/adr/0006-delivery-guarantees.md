# 6. Delivery guarantees: a derived event id, and keys recorded after the ack

- **Status:** Accepted
- **Date decided:** 2026-11-03
- **Date recorded:** 2026-11-03, with the change

## Context

The api's dedupe was a claim. `IdempotencyStore.claim` ran `SET NX` on the `Idempotency-Key`
with a fresh random event id, the service published, and if the publish threw it deleted the
key again so the client's retry would not be swallowed. The publish is awaited
(`notification.publish.timeout`, 5 s), which is what makes a 202 honest. That left three
holes:

- **(a) Timed out, but the broker took it.** The api stops waiting after 5 s; the producer
  does not. It keeps retrying the same send for up to `delivery.timeout.ms` (120 s by
  default) and may still land it. The api had already released the key and answered 5xx, so
  the client's retry got a new random event id: two events, two rows, two notifications.
- **(b) Redis down at release time.** The release failed with it, and the claim lived out
  its 24 h TTL. The client's retry was answered 202 `duplicate=true` with an event id that
  never reached the topic: a lost notification, reported as accepted.
- **(c) Same root, found while reading.** A concurrent request under the same key read the
  in-flight claim and got 202 `duplicate=true`. If the first publish then failed, that caller
  held a 202 for an event that never existed.

All three come from claiming the key before anything is known, which makes the failure path
an undo, and the undo is the step that can fail or be wrong.

The idempotent producer does not close (a). It stops the producer's *own* retries of one
send from writing twice (sequence numbers per producer id and partition). A second
`publish()` after a timeout is a new send with a new sequence number, and the broker writes
it.

## Decision

1. **The event id is derived from the key.** `EventIds.forIdempotencyKey(key)` is a version 5
   UUID (RFC 9562: SHA-1 over a fixed namespace, `EventIds.NAMESPACE`, then the key's UTF-8
   bytes). Without a key the id is still random. It lives in the api, not in
   notification-common: how the api dedupes is its policy, and the worker treats the id as
   opaque. The namespace never changes.
2. **Nothing is claimed before publishing; Redis records a key only after the ack.**
   1. `findPublished(key)` (GET). An entry means a replay: answer its id with
      `duplicate=true` and publish nothing.
   2. Derive the id and make the awaited publish. A throw is a 5xx with nothing to undo.
   3. `recordPublished(key, id)` (`SET NX EX ttl`). Won: `duplicate=false`. Lost to a
      concurrent request: `duplicate=true`.

   Every 202 now means the broker acknowledged that event id. The Redis key prefix and value
   format are unchanged, so old and new instances read each other's entries.
3. **Redis failures.** Before publishing, the request fails and nothing is published. Going
   on would be safe for delivery, but a replay would then be answered `duplicate=false`.
   After the ack, the api logs a warning and answers 202 `duplicate=false`. The event is on
   the topic, and a later retry finds no entry and republishes the same id, which the worker
   absorbs.
4. **The idempotent producer is set explicitly** in `KafkaProducerConfig`:
   `enable.idempotence=true` and `acks=all`, in code so no property can weaken them. It has
   been the default since Kafka 3.0, but it is dropped silently when another setting
   conflicts; asking for it makes that conflict a `ConfigException` at startup.
5. **A key names its event for as long as the delivery row exists.** Redis decides only the
   replay flag, and only within its TTL. A key reused after 24 h derives the original id, the
   worker absorbs it, and the api answers 202 `duplicate=false`. Keys must be unique per
   notification.

## Result

**At-least-once on the wire, exactly one row per event id in the table.**

- A timed-out publish and its retry are two records with the same event id. Both are keyed
  by recipient, so they share a partition and arrive in order.
- The worker absorbs the copy in `DeliveryRecorder.claim`, which looks the event id up first
  and skips a row that is already SENT. The primary key on `notification.event_id` is the
  backstop for the *row* -- a second insert cannot succeed -- not for the send.
- Delivery to the recipient stays at-least-once, as ADR 0004 says: a crash between the send
  and the SENT record sends again.
- Redis is no longer needed for correctness, only for the replay answer.

## Consequences

- **A 5xx from the POST means "not confirmed", not "not sent".** The client must retry under
  the same key; a retry under a new key is a new notification.
- A copy that arrives while the first is RETRYING, FAILED or DEAD_LETTERED is dispatched
  again against the same row. That is another try, not a second send, since nothing went
  out -- unless it meets a retry of the first copy coming off a retry topic at the same
  moment, when both can send. At-least-once, like the rest.
- **Keys are global, not per client.** Moot while there is one client credential, but with a
  real identity provider two clients' key `1` would be one notification. The derivation and
  the Redis key then need to be over the client and the key together.
- Reusing a key with a different body is not detected. The second body is ignored, as it
  always was.
- Two concurrent requests under one key can both publish, since both miss the GET. They carry
  the same id, the worker absorbs the copy, and only one is told `duplicate=false`.
- **Rolling deploy.** An old instance still claims before publishing, so while one runs, and
  for up to the 24 h TTL after the last one stops, a claim it left behind can still swallow a
  retry. A new instance reading an old claim answers the old, random id.
- `EventIds.NAMESPACE` is effectively part of the contract with clients' retries: changing it
  re-keys every retry in flight across that deploy and reopens (a).

## Alternatives considered

- **Transactional outbox in the api** -- write the request and the event in one local
  transaction, relay it later. It closes (a) and (b) outright, but it gives the api a schema
  and a relay, which reverses ADR 0003 (the api owns no tables), for a problem a deterministic
  id solves without state.
- **Kafka transactions / exactly-once semantics.** They make consume-transform-produce atomic
  inside Kafka. An HTTP request has no input offset to commit with the send, Redis cannot
  join the transaction, and a transactional send that times out leaves the same question
  open.
- **Keep the claim, add a "published" marker** -- claim with a pending value, flip it after
  the ack. Redundant once the id is derived: the claim carries nothing the key does not
  already give, and it keeps the undo step that (b) is about.
- **Keep claim and release, and retry the release.** Narrows (b) without closing it, since
  Redis down is down, and leaves (a) untouched.
- **Ask the worker's read api whether the event exists before publishing.** Couples the
  api's availability to the worker's, and races with an event still in flight on the topic.
- **Derive the id from a hash of the body.** Two genuinely separate, identical notifications
  would collapse into one. The key is the client's statement of identity; the body is not.
- **Lower `delivery.timeout.ms` to the publish timeout.** Narrows (a) without closing it: a
  request already with the broker can still be written after the api stops waiting.
