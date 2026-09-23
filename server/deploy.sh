#!/bin/bash
# Installs the Photo Gallery Screensaver OAuth relay on this host.
# Expects main.py, requirements.txt and the unit file in /tmp/relay-setup.
set -euo pipefail

SRC=/tmp/relay-setup
APP_DIR=/opt/photo-screensaver
CONF_DIR=/etc/photo-screensaver
UNIT=/etc/systemd/system/photo-screensaver-relay.service

echo '--- 1/7 service account ---'
if ! id screensaver >/dev/null 2>&1; then
  adduser --system --group --no-create-home --home "$APP_DIR" screensaver
fi

echo '--- 2/7 directories ---'
mkdir -p "$APP_DIR" "$CONF_DIR"
chown screensaver:screensaver "$APP_DIR"

echo '--- 3/7 application files ---'
install -o screensaver -g screensaver -m 644 "$SRC/main.py" "$APP_DIR/main.py"
install -o screensaver -g screensaver -m 644 "$SRC/requirements.txt" "$APP_DIR/requirements.txt"

echo '--- 4/7 virtualenv ---'
if [ ! -x "$APP_DIR/.venv/bin/python" ]; then
  python3 -m venv "$APP_DIR/.venv"
fi
"$APP_DIR/.venv/bin/pip" install --quiet --upgrade pip
"$APP_DIR/.venv/bin/pip" install --quiet -r "$APP_DIR/requirements.txt"
chown -R screensaver:screensaver "$APP_DIR/.venv"

echo '--- 5/7 configuration ---'
if [ ! -f "$CONF_DIR/relay.env" ]; then
  install -o screensaver -g screensaver -m 600 "$SRC/relay.env.example" "$CONF_DIR/relay.env"
  echo "CREATED $CONF_DIR/relay.env from the example - fill in the client credentials."
else
  echo "Keeping the existing $CONF_DIR/relay.env"
fi

echo '--- 6/7 systemd unit ---'
install -o root -g root -m 644 "$SRC/photo-screensaver-relay.service" "$UNIT"
systemctl daemon-reload
systemctl enable photo-screensaver-relay >/dev/null

echo '--- 7/7 start ---'
systemctl restart photo-screensaver-relay
sleep 3
systemctl is-active photo-screensaver-relay

echo
echo '--- local health check ---'
curl -s --max-time 5 http://127.0.0.1:9037/health || echo '(no response)'
echo
