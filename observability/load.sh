#!/usr/bin/env bash
# Sends a mix of notifications to the compose stack so the Grafana dashboard has something
# to show. The PowerShell twin is load.ps1; both send the same mix.
#
#   ./observability/load.sh
#   COUNT=60 DELAY_SECONDS=0.5 ./observability/load.sh
#
# Gets one token with the documented dev credential, then POSTs COUNT notifications to the
# api, DELAY_SECONDS apart, each under a fresh Idempotency-Key. Channels rotate EMAIL, SMS,
# PUSH. Every 10th request repeats the one before it, key and all, so the api answers it as a
# duplicate. Every 20th names a template the worker does not have, so it fails each try and
# is dead-lettered: the NotificationDeadLettered alert fires within half a minute.
#
# Keys carry a random run id, because MySQL's volume outlives `docker compose down` and a
# reused key names the earlier run's event (ADR 0006).
#
# Prints a tally, never the token. Exits 1 if no token came back, 2 if any request was
# answered with anything but 202. Needs bash and curl, nothing else.
set -u

COUNT="${COUNT:-300}"
DELAY_SECONDS="${DELAY_SECONDS:-0.2}"
API_HOST="${API_HOST:-http://localhost:8080}"

# The documented dev credential (application.properties, http-client.env.json), not a secret.
token=$(curl -sS --max-time 10 -X POST "$API_HOST/api/auth/token" \
    -H 'Content-Type: application/json' \
    -d '{"clientId":"notification-client","clientSecret":"dev-secret"}' 2>/dev/null \
  | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')
if [ -z "$token" ]; then
  echo "No token from $API_HOST/api/auth/token. Is the stack up (docker compose up --build)?" >&2
  exit 1
fi

channels=(EMAIL SMS PUSH)
destinations=(load-test@example.com +15550100 load-test-device)
run_id=$(head -c 4 /dev/urandom | od -An -tx1 | tr -d ' \n')
response=$(mktemp)
trap 'rm -f "$response"' EXIT

accepted=0
duplicate=0
other=0
other_statuses=
key=
body=

echo "Run $run_id: $COUNT requests to $API_HOST, ${DELAY_SECONDS}s apart"
for ((i = 1; i <= COUNT; i++)); do
  if (( i % 10 != 0 )) || [ -z "$key" ]; then
    c=$(( i % 3 ))
    template=order-shipped
    (( i % 20 == 7 )) && template=no-such-template
    key="load-$run_id-$i"
    body=$(printf '{"recipientId":"load-user-%d","destination":"%s","channel":"%s","templateKey":"%s","payload":{"orderId":"L-%d"}}' \
      $(( i % 25 )) "${destinations[$c]}" "${channels[$c]}" "$template" "$i")
  fi
  # Otherwise the 10th repeats the previous request exactly: same key, same body.

  status=$(curl -sS --max-time 10 -o "$response" -w '%{http_code}' \
      -X POST "$API_HOST/api/notifications" \
      -H 'Content-Type: application/json' \
      -H "Authorization: Bearer $token" \
      -H "Idempotency-Key: $key" \
      -d "$body" 2>/dev/null)
  if [ "$status" = 202 ] && grep -q '"duplicate":true' "$response"; then
    duplicate=$((duplicate + 1))
  elif [ "$status" = 202 ]; then
    accepted=$((accepted + 1))
  else
    other=$((other + 1))
    # 000 is curl giving up: no response at all.
    case " $other_statuses " in *" $status "*) ;; *) other_statuses="$other_statuses $status" ;; esac
  fi

  (( i % 50 == 0 )) && echo "  $i of $COUNT"
  sleep "$DELAY_SECONDS"
done

echo "Answers:"
printf '  %-12s %d\n' accepted "$accepted" duplicate "$duplicate"
[ "$other" -gt 0 ] && printf '  %-12s %d (HTTP%s)\n' other "$other" "$other_statuses"
echo "About $(( (COUNT + 13) / 20 )) of the accepted will dead-letter (no-such-template)."
echo "Dashboard: http://localhost:3000   Alerts: http://localhost:9090/alerts"

[ "$other" -gt 0 ] && exit 2
exit 0
