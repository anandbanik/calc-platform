#!/usr/bin/env bash
# Mints a dev HS256 JWT carrying tenant_id=<tenant>, valid for one hour.
# Usage: scripts/mint-token.sh uscard
# Uses the same dev secret as application.yml unless CALC_JWT_SECRET is set.
set -euo pipefail

tenant="${1:?usage: $0 <tenant-id>}"
secret="${CALC_JWT_SECRET:-dev-only-secret-do-not-use-in-production-0123456789}"
now=$(date +%s)

b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }

header=$(printf '{"alg":"HS256","typ":"JWT"}' | b64url)
payload=$(printf '{"sub":"dev-%s","tenant_id":"%s","iat":%d,"exp":%d}' "$tenant" "$tenant" "$now" "$((now + 3600))" | b64url)
signature=$(printf '%s.%s' "$header" "$payload" | openssl dgst -sha256 -hmac "$secret" -binary | b64url)

printf '%s.%s.%s\n' "$header" "$payload" "$signature"
