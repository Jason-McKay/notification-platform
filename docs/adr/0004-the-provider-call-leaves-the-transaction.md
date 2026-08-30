# 4. The provider call leaves the transaction

- **Status:** Accepted
- **Date decided:** 2026-08-30
- **Date recorded:** 2026-08-30, with the SMTP email channel

## Context

`DispatchService.dispatch` was one `@Transactional` method: find or create the delivery row,
render, hand the notification to a sender, mark it SENT. With `LoggingNotificationSender`
that was fine and the javadoc said so, deferring the question until a real provider arrived.

EMAIL over SMTP is that provider. Keeping the call where it was means a database transaction
stays open for the length of a network round trip to a mail server, holding a row lock the
whole time. A mail server that is merely slow then becomes a database problem: connections
held, locks held, and a pool that empties under exactly the load that makes the provider
slow in the first place. The failure spreads from the part of the system that broke to a
part that did not.

## Decision

- **`DispatchService` is not transactional at all.** It orchestrates, in this order:
  1. `DeliveryRecorder.claim` -- transactional, commits. Creates the PENDING row, or returns
     empty when the row is already SENT and this is a redelivery.
  2. `TemplateRenderer.render` -- pure, no transaction to be in.
  3. `NotificationSender.send` -- **no transaction open.**
  4. `DeliveryRecorder.recordSent` -- transactional, commits the SENT status and its attempt.
- **`DeliveryRecorder` owns every write.** That is also what makes the above true: a private
  `@Transactional` method on the dispatcher would be a self-call and would never reach the
  proxy, so the annotation would silently do nothing.
- **Failures are unchanged.** Anything thrown reaches the listener, which records the FAILED
  attempt and rethrows so the retry topics carry the event to the dead-letter topic.

## Consequences

- A slow provider costs the worker a listener thread, which is what it should cost. It no
  longer costs a database connection and a row lock.
- **A crash between steps 3 and 4 leaves the row PENDING with the message already
  delivered.** The retry then delivers it again. This is the trade, and it is at-least-once
  delivery stated out loud rather than hidden behind a transaction that never actually
  covered the provider anyway -- a commit cannot un-send an email.
- The status read can now show PENDING for a notification that has genuinely gone out. The
  window is milliseconds in normal operation and unbounded after a crash.
- Making this exactly-once needs the provider's own idempotency key, or an outbox with a
  reconciler. Both are worth it when a provider charges per message; neither is worth it for
  a mail catcher on a laptop.

## Alternatives considered

- **Leave the send inside the transaction.** Rejected: it is the arrangement that turns a
  slow provider into a database outage, and it never bought exactly-once regardless. The
  transaction can roll back the row; the email is already gone.
- **Outbox pattern** -- write the intent, let a separate poller deliver. The right answer at
  the point where losing or duplicating a message costs real money, and considerable
  machinery before then: a second table, a poller, its own retry story. Revisit with a paid
  provider.
- **Mark SENT before sending.** Rejected in the other direction: a failed send would then
  read as delivered, which is worse than a duplicate.
