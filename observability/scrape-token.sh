#!/bin/sh
# Keeps a dev bearer token in /token/scrape for Prometheus to scrape both apps with.
#
# Both apps' /actuator/prometheus take the read scope, and the dev tokens the api issues last
# an hour, so this fetches one from the api's dev token endpoint and refreshes it well inside
# that hour. Prometheus re-reads its credentials_file on every scrape, so a refresh needs no
# reload. Compose only: a real deployment gets its scrape credential from an identity
# provider. See docs/adr/0007-scraping-inside-compose.md.
#
# Never prints the token.
set -u

TOKEN_FILE=/token/scrape
TOKEN_URL="${TOKEN_URL:-http://np-api:8080/api/auth/token}"
REFRESH_SECONDS="${REFRESH_SECONDS:-1800}"

# PID 1 ignores SIGTERM unless it is trapped, and the trap only runs once the foreground
# command returns -- hence sleeping in the background and waiting on it.
trap 'exit 0' TERM INT

# A token left in the volume by an earlier run may have expired. Remove it, so it cannot pass
# the healthcheck before this run has fetched a fresh one.
rm -f "$TOKEN_FILE"

while true; do
  body=$(curl -fsS --max-time 10 -X POST "$TOKEN_URL" \
      -H 'Content-Type: application/json' \
      -d "{\"clientId\":\"$CLIENT_ID\",\"clientSecret\":\"$CLIENT_SECRET\"}")
  token=$(printf '%s' "$body" | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')
  if [ -n "$token" ]; then
    # Prometheus may read the file at any moment: write beside it, then rename over it.
    printf '%s' "$token" > "$TOKEN_FILE.tmp" && mv "$TOKEN_FILE.tmp" "$TOKEN_FILE"
    echo "scrape token refreshed; next refresh in ${REFRESH_SECONDS}s"
    sleep "$REFRESH_SECONDS" & wait $!
  else
    echo "no token from $TOKEN_URL yet; retrying in 5s"
    sleep 5 & wait $!
  fi
done
