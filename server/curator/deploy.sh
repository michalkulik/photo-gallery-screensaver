#!/bin/bash
# Installs the Synology Photos curator on this host.
# Expects the application files in /tmp/curator-setup.
set -euo pipefail

SRC=/tmp/curator-setup
APP_DIR=/opt/photo-curator
CONF_DIR=/etc/photo-curator
STATE_DIR=/var/lib/photo-curator
UNIT=/etc/systemd/system/photo-curator.service

echo '--- 1/7 service account ---'
if ! id photocurator >/dev/null 2>&1; then
  adduser --system --group --no-create-home --home "$APP_DIR" photocurator
fi

echo '--- 2/7 directories ---'
mkdir -p "$APP_DIR" "$CONF_DIR" "$STATE_DIR"
chown photocurator:photocurator "$APP_DIR" "$STATE_DIR"
chmod 750 "$STATE_DIR"

echo '--- 3/7 application files ---'
install -o photocurator -g photocurator -m 644 "$SRC/syno.py" "$APP_DIR/syno.py"
install -o photocurator -g photocurator -m 644 "$SRC/curator.py" "$APP_DIR/curator.py"
install -o photocurator -g photocurator -m 644 "$SRC/main.py" "$APP_DIR/main.py"
install -o photocurator -g photocurator -m 644 "$SRC/requirements.txt" "$APP_DIR/requirements.txt"

echo '--- 4/7 virtualenv ---'
if [ ! -x "$APP_DIR/.venv/bin/python" ]; then
  python3 -m venv "$APP_DIR/.venv"
fi
"$APP_DIR/.venv/bin/pip" install --quiet --upgrade pip
"$APP_DIR/.venv/bin/pip" install --quiet -r "$APP_DIR/requirements.txt"
chown -R photocurator:photocurator "$APP_DIR/.venv"

echo '--- 5/7 configuration ---'
if [ ! -f "$CONF_DIR/curator.env" ]; then
  install -o photocurator -g photocurator -m 600 "$SRC/curator.env.example" "$CONF_DIR/curator.env"
  echo "CREATED $CONF_DIR/curator.env - adjust it, then restart the service."
else
  echo "Keeping the existing $CONF_DIR/curator.env"
fi

echo '--- 6/7 systemd unit ---'
install -o root -g root -m 644 "$SRC/photo-curator.service" "$UNIT"
systemctl daemon-reload
systemctl enable photo-curator >/dev/null

echo '--- 7/7 start ---'
systemctl restart photo-curator
sleep 4
systemctl is-active photo-curator

echo
echo '--- health ---'
curl -s --max-time 5 http://127.0.0.1:9038/health || echo '(no response)'
echo
