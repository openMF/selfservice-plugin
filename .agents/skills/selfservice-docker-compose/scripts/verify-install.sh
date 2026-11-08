#!/bin/sh
# Check a Docker Compose install of the self service plugin: Fineract health, admin login,
# plugin JAR mounted, and the plugin's /v1/self/authentication endpoint answering.
# Waits up to 10 minutes for Fineract to report UP. Exits 0 only if all checks pass,
# 1 if a check fails, 2 if the stack isn't running or curl is missing.
# Usage: verify-install.sh   (run from the repository root, where docker-compose.yml is)
# No set -e: a failing check must still reach the report below.
[ -z "${ZSH_VERSION:-}" ] || emulate sh
set -u

BASE=https://localhost:8443/fineract-provider
WAIT_SECONDS=600

# Self-signed certificate (-k); a proxy from the environment must not handle localhost.
local_curl() { curl -k --noproxy '*' -s -m 20 "$@"; }

# Print the response body, a newline, then the HTTP status code.
curl_with_code() { local_curl -w '\n%{http_code}' "$@"; }

# Overall health is UP only with HTTP 200: Spring Boot answers 503 when the top-level status is
# DOWN, even if a component inside the body reports "status":"UP".
health_up() {
  [ "${health##*"$NL"}" = 200 ] || return 1
  case "${health%"$NL"*}" in *'"status":"UP"'*) return 0 ;; *) return 1 ;; esac
}
NL='
'

command -v curl >/dev/null || { echo "error: curl is needed for the checks" >&2; exit 2; }
if [ -z "$(docker compose ps -q fineract 2>/dev/null)" ]; then
  echo "error: fineract isn't running; run 'docker compose up -d' in the repository root first" >&2
  exit 2
fi

health=""
deadline=$(( $(date +%s) + WAIT_SECONDS ))
while :; do
  health=$(curl_with_code "$BASE/actuator/health")
  health_up && break
  [ "$(date +%s)" -ge "$deadline" ] && break
  # A container that exited won't come up; don't wait the full 10 minutes for it.
  [ -z "$(docker compose ps -aq --status exited fineract 2>/dev/null)" ] || break
  sleep 10
done

# Exit status, not output: ls's "No such file" message also contains the file name.
docker compose exec -T fineract test -f /app/plugins/selfservice-plugin.jar 2>/dev/null; jar_rc=$?
admin=$(local_curl -X POST "$BASE/api/v1/authentication" \
  -H 'Content-Type: application/json' -H 'Fineract-Platform-TenantId: default' \
  -d '{"username":"mifos","password":"password"}')
# The plugin's validator answers {} with 400 and its own username and password error codes.
# Without the plugin, Fineract answers 401.
self_empty=$(curl_with_code -X POST "$BASE/api/v1/self/authentication" \
  -H 'Content-Type: application/json' -H 'Fineract-Platform-TenantId: default' -d '{}')
self_code=${self_empty##*"$NL"}
self_body=${self_empty%"$NL"*}

failed=0
# Print a PASS or FAIL line and remember a failure. Usage: report <check> <ok:0|1> <detail>
report() {
  if [ "$2" -eq 0 ]; then echo "PASS  $1: $3"; else echo "FAIL  $1: $3"; failed=1; fi
}
if health_up; then
  report "Fineract health" 0 "UP"
else
  body=$(printf '%s' "${health%"$NL"*}" | head -c 200)
  report "Fineract health" 1 "not UP (HTTP ${health##*"$NL"}: ${body:-no response})"
fi
if [ "$jar_rc" -eq 0 ]; then
  report "Plugin JAR mounted" 0 "/app/plugins/selfservice-plugin.jar"
else
  report "Plugin JAR mounted" 1 "/app/plugins/selfservice-plugin.jar not in the fineract container"
fi
login="Admin login mifos/default tenant"
case "$admin" in
  *'"authenticated":true'*) report "$login" 0 '"authenticated":true' ;;
  *) report "$login" 1 "$(printf '%s' "${admin:-no response}" | head -c 200)" ;;
esac
probe="POST /v1/self/authentication {}"
case "$self_code:$self_body" in
  400:*selfservice.authentication.username*selfservice.authentication.password*)
    report "Self service endpoint" 0 "$probe -> 400 (plugin validator)" ;;
  *) report "Self service endpoint" 1 \
       "$probe -> HTTP ${self_code:-none}, expected 400 with the plugin's validation errors" ;;
esac

if [ $failed -ne 0 ]; then
  echo
  echo "Last 50 lines of the fineract log:"
  docker compose logs --tail=50 fineract
fi
exit $failed
