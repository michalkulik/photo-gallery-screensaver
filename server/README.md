# OAuth relay

A tiny FastAPI service that lets the Android TV app sign in to Google Photos.

## Why it is needed

The app cannot sign in to Google on its own:

* **Google's device flow** (the "type a code on your phone" flow) only allows a small
  allow-list of scopes — OpenID, Drive and YouTube. The Photos Picker scope is not on it, so
  the endpoint answers `invalid device flow scope`.
* **There is no browser** on the TV (the PlayBox only ships a browser *stub*).
* **Google blocks OAuth inside a WebView** (`disallowed_useragent`).

So the OAuth dance is moved to this service: the TV shows a QR code, the user signs in on their
phone through an ordinary browser redirect, and the TV collects the resulting tokens.

The service deliberately does **not** call the Photos Picker API. It only handles OAuth; the app
keeps managing picker sessions itself.

## Token delivery

Two separate identifiers are used, so photographing the QR code is not enough to steal tokens:

| Value | Visibility | Purpose |
|---|---|---|
| `id` | secret, returned only to the TV | polls for the tokens |
| `flow_token` | public, embedded in the QR code | used as the OAuth `state` |

The client secret never leaves the server. The app stores only the refresh token, and asks
`/api/refresh` for access tokens, so a leaked APK contains no secret.

## Endpoints

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/health` | liveness plus whether credentials are configured |
| `POST` | `/api/session` | starts a sign-in, returns `{id, auth_url, poll_interval_seconds}` |
| `GET` | `/api/session/{id}` | `pending` / `ready` (with tokens) / `error`; `404` once expired |
| `DELETE` | `/api/session/{id}` | drops a session as soon as the TV stored the tokens |
| `POST` | `/api/refresh` | trades a refresh token for an access token |
| `GET` | `/s/{flow_token}` | what the QR code opens; redirects the phone to Google |
| `GET` | `/oauth/callback` | Google redirect target; exchanges the code for tokens |
| `GET` | `/privacy` | privacy note, useful for the Google consent screen |

Sessions live in memory only, are never written to disk, and expire after 15 minutes
(`SESSION_TTL_SECONDS`); a completed session is dropped 5 minutes after the TV collects it
(`DELIVERY_TTL_SECONDS`).

## Google Cloud setup

1. Enable the **Photos Picker API** for the project.
2. Create an OAuth client of type **Web application**.
   *Note: the client type cannot be changed later, and "TVs and Limited Input devices" is the
   wrong type for this flow — the device flow cannot request the Picker scope.*
3. Add this authorised redirect URI:
   `https://screensaver.mkulik.eu/oauth/callback`
4. Put the client id and secret into `/etc/photo-screensaver/relay.env`:

```
GOOGLE_CLIENT_ID=...
GOOGLE_CLIENT_SECRET=...
PUBLIC_BASE_URL=https://screensaver.mkulik.eu
```

5. Restart and check:

```bash
sudo systemctl restart photo-screensaver-relay
curl -s https://screensaver.mkulik.eu/health   # -> "configured":true
```

## Deployment

`deploy.sh` installs everything: a `screensaver` system account, a virtualenv under
`/opt/photo-screensaver`, the config in `/etc/photo-screensaver/relay.env`, and the systemd unit.
It is idempotent and never overwrites an existing `relay.env`.

```bash
# from the repository root
scp server/main.py server/requirements.txt server/deploy.sh \
    server/photo-screensaver-relay.service server/relay.env.example \
    <host>:/tmp/relay-setup/
ssh <host> "sed -i 's/\r$//' /tmp/relay-setup/*; bash /tmp/relay-setup/deploy.sh"
```

The service listens on `127.0.0.1:9037`-style plain HTTP; TLS is terminated by the reverse proxy
in front of it (Nginx Proxy Manager on this host), which forwards `screensaver.mkulik.eu` to port
`9037`.

> The `sed` step matters: the repository is edited on Windows, and CRLF line endings break both
> `bash` and the systemd unit file.

## Local development

```bash
python3 -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt
PUBLIC_BASE_URL=http://localhost:9037 GOOGLE_CLIENT_ID=... GOOGLE_CLIENT_SECRET=... \
  uvicorn main:app --port 9037
```
