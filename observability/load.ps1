<#
.SYNOPSIS
  Sends a mix of notifications to the compose stack so the Grafana dashboard has something
  to show.

.DESCRIPTION
  Gets one token with the documented dev credential, then POSTs -Count notifications to the
  api, -DelayMs apart, each under a fresh Idempotency-Key. Channels rotate EMAIL, SMS, PUSH.
  Every 10th request repeats the one before it, key and all, so the api answers it as a
  duplicate. Every 20th names a template the worker does not have, so it fails each try and
  is dead-lettered: the NotificationDeadLettered alert fires a few seconds later.

  Keys carry a random run id, because MySQL's volume outlives `docker compose down` and a
  reused key names the earlier run's event (ADR 0006).

  Prints a tally, never the token. Exits 1 if no token came back, 2 if any request was
  answered with anything but 202. The bash twin is load.sh.

.EXAMPLE
  .\observability\load.ps1
  .\observability\load.ps1 -Count 60 -DelayMs 500
#>
[CmdletBinding()]
param(
  [int] $Count = 300,
  [int] $DelayMs = 200,
  [string] $ApiHost = 'http://localhost:8080'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
# Windows PowerShell redraws a progress bar per request, which is slower than the requests.
$ProgressPreference = 'SilentlyContinue'

# The documented dev credential (application.properties, http-client.env.json), not a secret.
$credential = @{ clientId = 'notification-client'; clientSecret = 'dev-secret' } |
  ConvertTo-Json -Compress
try {
  $token = (Invoke-RestMethod -Method Post -Uri "$ApiHost/api/auth/token" `
    -ContentType 'application/json' -Body $credential).accessToken
} catch {
  $token = $null
}
if (-not $token) {
  Write-Error "No token from $ApiHost/api/auth/token. Is the stack up (docker compose up --build)?" `
    -ErrorAction Continue
  exit 1
}

$channels = @(
  @{ channel = 'EMAIL'; destination = 'load-test@example.com' },
  @{ channel = 'SMS'; destination = '+15550100' },
  @{ channel = 'PUSH'; destination = 'load-test-device' }
)
$runId = [guid]::NewGuid().ToString('N').Substring(0, 8)
$tally = [ordered]@{ accepted = 0; duplicate = 0 }
$key = $null
$body = $null

Write-Host "Run $runId`: $Count requests to $ApiHost, ${DelayMs}ms apart"
for ($i = 1; $i -le $Count; $i++) {
  if ($i % 10 -ne 0 -or $null -eq $key) {
    $target = $channels[$i % 3]
    $template = if ($i % 20 -eq 7) { 'no-such-template' } else { 'order-shipped' }
    $key = "load-$runId-$i"
    $body = @{
      recipientId = "load-user-$($i % 25)"
      destination = $target.destination
      channel = $target.channel
      templateKey = $template
      payload = @{ orderId = "L-$i" }
    } | ConvertTo-Json -Compress
  }
  # Otherwise the 10th repeats the previous request exactly: same key, same body.

  $headers = @{ Authorization = "Bearer $token"; 'Idempotency-Key' = $key }
  try {
    $response = Invoke-WebRequest -UseBasicParsing -Method Post -Uri "$ApiHost/api/notifications" `
      -ContentType 'application/json' -Headers $headers -Body $body -TimeoutSec 10
    $outcome = if (($response.Content | ConvertFrom-Json).duplicate) { 'duplicate' } else { 'accepted' }
  } catch {
    $status = $null
    if ($_.Exception.PSObject.Properties['Response'] -and $_.Exception.Response) {
      $status = [int] $_.Exception.Response.StatusCode
    }
    $outcome = if ($status) { "http $status" } else { 'no response' }
  }
  if (-not $tally.Contains($outcome)) { $tally[$outcome] = 0 }
  $tally[$outcome]++

  if ($i % 50 -eq 0) { Write-Host "  $i of $Count" }
  Start-Sleep -Milliseconds $DelayMs
}

Write-Host 'Answers:'
foreach ($entry in $tally.GetEnumerator()) {
  Write-Host ('  {0,-12} {1}' -f $entry.Key, $entry.Value)
}
$dead = [math]::Floor(($Count + 13) / 20)
Write-Host "About $dead of the accepted will dead-letter (no-such-template)."
Write-Host 'Dashboard: http://localhost:3000   Alerts: http://localhost:9090/alerts'

if ($tally.Count -gt 2) { exit 2 }
exit 0
