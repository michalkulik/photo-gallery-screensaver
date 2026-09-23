#!/bin/bash
# Validates the NPM advanced config in a throwaway nginx container.
# Builds a server block shaped exactly like the one NPM generates, with the advanced config
# inserted at server level, then asks nginx to check it - and then exercises the allowlist by
# sending real requests through a running nginx.
#
# Usage, from the repository root:
#     bash tools/test-npm-config.sh
set -u

REPO_ROOT=$(cd "$(dirname "$0")/.." && pwd)
CONF="$REPO_ROOT/docs/synoscreensaver-npm-advanced.conf"
TESTDIR=/tmp/nginx-allowlist-test

if [ ! -f "$CONF" ]; then
  echo "cannot find $CONF"
  exit 1
fi

rm -rf "$TESTDIR"
mkdir -p "$TESTDIR/conf.d/include"

# A stand-in for NPM's proxy.conf, so the config under test is unchanged.
cat > "$TESTDIR/conf.d/include/proxy.conf" <<'EOF'
proxy_set_header Host $host;
proxy_pass $forward_scheme://$server:$port$request_uri;
EOF

# The server block NPM generates, with the advanced config inlined where NPM puts it.
{
  echo 'events {}'
  echo 'http {'
  echo '  server {'
  echo '    listen 8080;'
  echo '    server_name synoscreensaver.mkulik.eu;'
  echo '    set $forward_scheme http;'
  echo '    set $server "127.0.0.1";'
  echo '    set $port 9;'
  echo
  sed 's/^/    /' "$CONF"
  echo
  echo '    location / {'
  echo '      include conf.d/include/proxy.conf;'
  echo '    }'
  echo '  }'
  echo '}'
} > "$TESTDIR/nginx.conf"

echo "=== nginx -t on the composed server block ==="
docker run --rm -v "$TESTDIR":/etc/nginx nginx:alpine nginx -t 2>&1 | tail -5

echo
echo "=== allowlist behaviour (start a real nginx and send requests) ==="
echo "    The locations proxy to the real NAS, so a 200 means the call went through."
echo "    DSM answers HTTP 200 even for its own errors (the body carries success:false)."
docker rm -f allowlist-test >/dev/null 2>&1
docker run -d --name allowlist-test -p 18099:8080 -v "$TESTDIR":/etc/nginx nginx:alpine >/dev/null
sleep 2

check() {
  local method="$1" url="$2" expect="$3" label="$4"
  local code
  code=$(curl -s -o /dev/null -w '%{http_code}' -X "$method" "http://127.0.0.1:18099$url")
  local mark="ok"
  [ "$code" = "$expect" ] || mark="MISMATCH (expected $expect)"
  printf '  %-6s %-3s  %-58s %s\n' "$method" "$code" "$label" "$mark"
}

check GET "/webapi/query.cgi?api=SYNO.API.Info&version=1&method=query&query=all" 200 "discovery"
check GET "/webapi/entry.cgi?api=SYNO.API.Encryption&version=1&method=getinfo" 200 "encryption"
check GET "/webapi/entry.cgi?api=SYNO.API.Auth&version=7&method=login&account=x&passwd=y" 200 "login"
check GET "/webapi/entry.cgi?api=SYNO.Foto.Browse.Album&version=5&method=list" 200 "album list"
check GET "/webapi/entry.cgi?api=SYNO.Foto.Browse.Item&version=6&method=list" 200 "photo list"
check GET "/webapi/entry.cgi?api=SYNO.Foto.Download&version=2&method=download" 200 "photo bytes"

echo
echo "  --- everything below must be refused ---"
check GET "/webapi/entry.cgi?api=SYNO.FileStation.List&version=2&method=list" 403 "File Station (file access!)"
check GET "/webapi/entry.cgi?api=SYNO.FileStation.Download&version=2&method=download" 403 "File Station download"
check GET "/webapi/entry.cgi?api=SYNO.Foto.Browse.Folder&version=2&method=list" 403 "browse folders"
check GET "/webapi/entry.cgi?api=SYNO.Core.User&version=1&method=list" 403 "list DSM users"
check GET "/webapi/entry.cgi?api=SYNO.Foto.Delete&version=1&method=delete" 403 "delete photos"
check GET "/webapi/entry.cgi?api=SYNO.Foto.Browse.NormalAlbum&version=4&method=add_item" 403 "add to album"
check GET "/webapi/entry.cgi?api=SYNO.Foto.DownloadExtra" 403 "prefix of an allowed name"
check GET "/webapi/entry.cgi?api=SYNO.Foto.Download%00" 403 "allowed name with a null byte"
check GET "/webapi/query.cgi?api=SYNO.Core.System" 403 "query.cgi with a foreign api"
check GET "/webapi/entry.cgi" 404 "entry.cgi with no query string at all"
check GET "/webapi/auth.cgi?api=SYNO.API.Auth" 404 "the other auth script"
check GET "/" 404 "the DSM web UI"
check GET "/photo/" 404 "the Photos web UI"
check GET "/webman/index.cgi" 404 "the DSM desktop"
check GET "/webapi/entry.cgi/../../etc/passwd" 404 "path traversal"
check POST "/webapi/entry.cgi?api=SYNO.Foto.Download&version=2&method=download" 405 "POST"

echo
echo "  --- the ACME challenge must reach the ACME location, not be refused ---"
code=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:18099/.well-known/acme-challenge/token")
if [ "$code" = "403" ] || [ "$code" = "404" ]; then
  printf '  %-3s  %-58s %s\n' "$code" "ACME challenge" "MISMATCH (blocked: renewal would break)"
else
  printf '  %-3s  %-58s %s\n' "$code" "ACME challenge" "ok (reaches the ACME handler)"
fi

docker rm -f allowlist-test >/dev/null 2>&1
rm -rf "$TESTDIR"
