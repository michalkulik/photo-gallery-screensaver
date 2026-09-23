#!/bin/bash
# Decides whether a Synology sign-in failure is caused by the credentials or by how the
# password is encrypted.
#
# It performs the SAME protocol twice against the NAS:
#   A) the password RSA-encrypted (what the Android app sends)
#   B) the password in the clear over HTTPS (what the reference Python client sends)
#
# If A fails and B works, the encryption step is at fault. If both fail, the account or
# password is. The password is read silently and never echoed.
#
# WARNING: each run performs real login attempts, which count towards DSM's Auto Block.
# Whitelist this machine in DSM first (Control Panel > Security > Auto Block).

set -uo pipefail

read -rp "NAS address [100.68.69.157]: " NAS
NAS=${NAS:-100.68.69.157}

read -rp "Port [5001]: " PORT
PORT=${PORT:-5001}

read -rp "Account: " ACCOUNT
read -rsp "Password: " PASSWORD
echo

BASE="https://$NAS:$PORT"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

echo
echo "=== DSM version ==="
curl -sk --max-time 15 "$BASE/webapi/query.cgi?api=SYNO.API.Info&version=1&method=query&query=SYNO.API.Auth" \
  | python3 -c 'import sys,json; d=json.load(sys.stdin); print("auth API maxVersion:", d["data"]["SYNO.API.Auth"]["maxVersion"])' 2>/dev/null

echo
echo "=== fetching the RSA public key ==="
curl -sk --max-time 15 "$BASE/webapi/entry.cgi?api=SYNO.API.Encryption&version=1&method=getinfo" \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["data"]["public_key"])' > "$TMP/pub.b64" 2>/dev/null
{
  echo "-----BEGIN PUBLIC KEY-----"
  fold -w 64 "$TMP/pub.b64"
  echo "-----END PUBLIC KEY-----"
} > "$TMP/pub.pem"
if ! openssl pkey -pubin -in "$TMP/pub.pem" -noout >/dev/null 2>&1; then
  echo "Could not read the NAS public key - is that really a Synology at $BASE ?"
  exit 1
fi
echo "ok"

echo
echo "=== A) login with the RSA-encrypted password (what the app does) ==="
printf '%s' "$PASSWORD" \
  | openssl pkeyutl -encrypt -pubin -inkey "$TMP/pub.pem" -pkeyopt rsa_padding_mode:pkcs1 \
  | base64 -w0 > "$TMP/enc.b64"

RESP_A=$(curl -sk --max-time 20 "$BASE/webapi/entry.cgi" \
  --data-urlencode "api=SYNO.API.Auth" \
  --data-urlencode "version=7" \
  --data-urlencode "method=login" \
  --data-urlencode "account=$ACCOUNT" \
  --data-urlencode "passwd@$TMP/enc.b64" \
  --data-urlencode "session=Photos" \
  --data-urlencode "format=sid")
echo "$RESP_A"

echo
echo "=== B) login with the password in the clear over HTTPS (reference client behaviour) ==="
RESP_B=$(curl -sk --max-time 20 "$BASE/webapi/entry.cgi" \
  --data-urlencode "api=SYNO.API.Auth" \
  --data-urlencode "version=7" \
  --data-urlencode "method=login" \
  --data-urlencode "account=$ACCOUNT" \
  --data-urlencode "passwd=$PASSWORD" \
  --data-urlencode "session=Photos" \
  --data-urlencode "format=sid" \
  --data-urlencode "logintype=local" \
  --data-urlencode "client=browser" \
  --data-urlencode "enable_syno_token=yes")
echo "$RESP_B"

echo
echo "=== verdict ==="
python3 - "$RESP_A" "$RESP_B" <<'PY'
import sys, json

def code(raw):
    try:
        d = json.loads(raw)
    except Exception:
        return "unparseable"
    if d.get("success"):
        return "SUCCESS"
    return (d.get("error") or {}).get("code", "?")

a, b = code(sys.argv[1]), code(sys.argv[2])
print(f"  A (RSA-encrypted): {a}")
print(f"  B (plaintext)    : {b}")
print()
if a == "SUCCESS" and b == "SUCCESS":
    print("  Both work. The app's encryption is fine.")
elif b == "SUCCESS" and a != "SUCCESS":
    print("  Only plaintext works -> the RSA encryption step in the app is wrong.")
elif a == "SUCCESS":
    print("  Only the encrypted form works.")
elif a == 403 or b == 403:
    print("  The account needs a two-factor code. Retry in the app and enter the 6-digit code.")
elif a == 407 or b == 407:
    print("  This machine is Auto Blocked. Whitelist it in DSM, then run again.")
elif a == 400 or b == 400:
    print("  The NAS rejects these credentials as wrong, on both paths.")
    print("  That points at the ACCOUNT NAME or the password itself, not the encryption.")
    print("  Check the DSM username (not the e-mail) under Control Panel > User & Group.")
else:
    print("  Unexpected. Compare the two responses above.")
PY
