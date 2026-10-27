#!/usr/bin/env bash
# Prueba de humo de auth-service contra una instancia corriendo.
#   ./scripts/smoke.sh [base_url]     (por defecto http://localhost:8081)
# Requiere curl y node (para leer JSON).
set -euo pipefail
BASE="${1:-http://localhost:8081}"
EMAIL="smoke.$(date +%s)@escuelaing.edu.co"
PASS="clave1234"
ok() { printf '  ✓ %s\n' "$1"; }
fail() { printf '  ✗ %s\n' "$1"; exit 1; }
json() { node -e "let d='';process.stdin.on('data',c=>d+=c).on('end',()=>{const o=JSON.parse(d);console.log($1)})"; }
req() { curl -s -o /tmp/smoke_body -w '%{http_code}' "$@"; }

echo "auth-service en $BASE"

[ "$(req "$BASE/health")" = 200 ] && ok "GET /health" || fail "health $(cat /tmp/smoke_body)"
[ "$(req "$BASE/metrics")" = 200 ] && grep -q festa_outbox_pending /tmp/smoke_body && ok "GET /metrics" || fail "metrics"
[ "$(req "$BASE/.well-known/jwks.json")" = 200 ] && ok "GET /.well-known/jwks.json" || fail "jwks"

code=$(req -X POST "$BASE/auth/register" -H 'content-type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PASS\",\"displayName\":\"Smoke Test\",\"accountType\":\"merchant\"}")
[ "$code" = 201 ] && ok "POST /auth/register → 201" || fail "register $code $(cat /tmp/smoke_body)"
ACCESS=$(json o.accessToken < /tmp/smoke_body)
REFRESH=$(json o.refreshToken < /tmp/smoke_body)

code=$(req -X POST "$BASE/auth/register" -H 'content-type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PASS\",\"displayName\":\"Smoke Test\",\"accountType\":\"user\"}")
[ "$code" = 409 ] && ok "registro repetido → 409 EMAIL_TAKEN" || fail "duplicado $code"

code=$(req -X POST "$BASE/auth/register" -H 'content-type: application/json' \
  -d '{"email":"x@gmail.com","password":"corta","displayName":"","accountType":"admin"}')
[ "$code" = 400 ] && ok "validaciones → 400 ($(json 'o.error.details.map(d=>d.field).join(",")' < /tmp/smoke_body))" || fail "validación $code"

code=$(req -X POST "$BASE/auth/register" -H 'content-type: application/json' \
  -d '{"email":"ana@gmail.com","password":"clave1234","displayName":"Ana","accountType":"user"}')
[ "$code" = 403 ] && ok "dominio no permitido → 403" || fail "dominio $code"

[ "$(req "$BASE/auth/me" -H "Authorization: Bearer $ACCESS")" = 200 ] && \
  [ "$(json o.role < /tmp/smoke_body)" = merchant ] && ok "GET /auth/me (role=merchant)" || fail "me"
[ "$(req "$BASE/auth/me")" = 401 ] && ok "GET /auth/me sin token → 401" || fail "me sin token"

code=$(req -X POST "$BASE/auth/login" -H 'content-type: application/json' -d "{\"email\":\"$EMAIL\",\"password\":\"mala1234\"}")
[ "$code" = 401 ] && ok "login con clave errada → 401" || fail "login malo $code"
code=$(req -X POST "$BASE/auth/login" -H 'content-type: application/json' -d "{\"email\":\"${EMAIL^^}\",\"password\":\"$PASS\"}")
[ "$code" = 200 ] && ok "login (correo en mayúsculas) → 200" || fail "login $code"

code=$(req -X POST "$BASE/auth/refresh" -H 'content-type: application/json' -d "{\"refreshToken\":\"$REFRESH\"}")
[ "$code" = 200 ] && ok "refresh → 200 (rota)" || fail "refresh $code"
NEW_REFRESH=$(json o.refreshToken < /tmp/smoke_body)
code=$(req -X POST "$BASE/auth/refresh" -H 'content-type: application/json' -d "{\"refreshToken\":\"$REFRESH\"}")
[ "$code" = 401 ] && ok "reusar refresh viejo → 401" || fail "reuso $code"
code=$(req -X POST "$BASE/auth/refresh" -H 'content-type: application/json' -d "{\"refreshToken\":\"$NEW_REFRESH\"}")
[ "$code" = 401 ] && ok "tras el reuso, toda la familia queda revocada → 401" || fail "familia $code"

[ "$(req -X DELETE "$BASE/auth/me" -H "Authorization: Bearer $ACCESS")" = 204 ] && ok "DELETE /auth/me → 204" || fail "delete"
code=$(req -X POST "$BASE/auth/login" -H 'content-type: application/json' -d "{\"email\":\"$EMAIL\",\"password\":\"$PASS\"}")
[ "$code" = 401 ] && ok "cuenta borrada no entra → 401" || fail "login tras borrar $code"

echo "OK"
