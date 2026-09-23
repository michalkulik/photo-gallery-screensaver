# Synology Photos curator

Keeps a Synology Photos album topped up with a fresh, non-repeating selection of photos.

Once a day it **replaces** the contents of one album with **one photo per day** from the last 90
days. A photo is never used twice: everything it has ever added is recorded in SQLite, and later
runs skip those photos. When a day has no photo left — because it had none, or because everything
from it was already used — the curator walks further back until it has filled the album.

## What it does, precisely

1. Signs in to the NAS (unattended, using a stored device token).
2. Reads the photos of the shared space, newest first.
3. Picks one unused photo per calendar day, newest day first, skipping days that have nothing
   left to offer.
4. If the window cannot fill the album, it **doubles the window** (90 → 180 → 360 …) and tries
   again, up to `CURATOR_MAX_WINDOW_DAYS`.
5. Removes every current item from the album, adds the new selection, and records what it used.

The photos are only ever **removed from the album**, never deleted from the library.

## Setup

The service listens on port **9038**. Nothing about the Android TV app depends on it.

### 1. Deploy

```bash
# from the repository root
scp server/curator/{syno.py,curator.py,main.py,requirements.txt,deploy.sh,photo-curator.service,curator.env.example} \
    <host>:/tmp/curator-setup/
ssh <host> "sed -i 's/\r$//' /tmp/curator-setup/*; bash /tmp/curator-setup/deploy.sh"
```

> The `sed` step matters: the repository is edited on Windows and CRLF line endings break both
> `bash` and the systemd unit.

`deploy.sh` creates the `photocurator` account, a virtualenv in `/opt/photo-curator`, config in
`/etc/photo-curator/curator.env`, state in `/var/lib/photo-curator`, and the systemd unit. It is
idempotent and never overwrites an existing `curator.env`.

### 2. Sign in

Open `http://<host>:9038/` and use the **Sign in to the NAS** form. This is the only step that
needs a person: an account with two-factor authentication must supply a code once.

The code is not stored. The NAS returns a **device token**, which is saved instead and lets every
later run sign in unattended. Tick **Verify TLS certificate** only if the certificate actually
matches the address you connect to — a NAS reached by IP while its certificate is issued for a
hostname needs it left off.

The password is written to `/var/lib/photo-curator/config.json` with mode `0600`. To avoid storing
it at all, leave the password field empty and set `SYNO_PASSWORD` in `curator.env` instead
(environment values win over the file).

### 3. Check it

Press **Run now** and read the run table. Or from a shell:

```bash
curl -s -X POST http://127.0.0.1:9038/api/run
journalctl -u photo-curator -n 40 --no-follow
```

Set **Dry run** in the Selection form to compute a selection without touching the album.

## Configuration

`/etc/photo-curator/curator.env`:

| Variable | Default | Meaning |
|---|---|---|
| `SYNO_HOST` | — | NAS address |
| `SYNO_PORT` | `5001` | WebAPI port (5001 HTTPS, 5000 HTTP) |
| `SYNO_ACCOUNT` | — | DSM account name, not the e-mail |
| `SYNO_PASSWORD` | — | Optional; overrides the stored password |
| `SYNO_VERIFY_TLS` | `false` | Verify the certificate and hostname |
| `CURATOR_ALBUM` | `ostatnie_najciekawsze` | Album to fill |
| `CURATOR_DAYS` | `90` | Photos, one per day |
| `CURATOR_RUN_HOUR` | `4` | Hour of the daily run, server local time |
| `CURATOR_SHARED_SPACE` | `true` | Read from the shared space instead of the personal one |
| `CURATOR_MAX_WINDOW_DAYS` | `3650` | How far back the window may grow |
| `CURATOR_DRY_RUN` | `false` | Compute without changing the album |

## Endpoints

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/` | Setup page and run history |
| `GET` | `/health` | Liveness and whether the NAS is configured |
| `GET` | `/api/status` | Configuration and the last runs, as JSON |
| `POST` | `/api/run` | Run now |
| `POST` | `/api/login` | Sign in and store the device token (form) |
| `POST` | `/api/config` | Change album, days, hour, space, dry run (form) |
| `POST` | `/api/forget` | Clear the used-photo history |

## State

`/var/lib/photo-curator/curator.db` (SQLite):

* `used_items` — every photo the curator has ever added, which is the exclusion list.
* `runs` — one row per run: status, how many items were added and removed, the window used.

Deleting `used_items` (or pressing **Forget used photos**) makes every photo available again.

## Design notes

Three things about the Synology API are unusual and are handled in `syno.py`:

* Over HTTPS the password must be sent **in the clear**. DSM compares `passwd` literally when TLS
  already protects it; an RSA-wrapped value is rejected as a wrong password, which looks exactly
  like a bad credential.
* `enable_syno_token` must **not** be requested. It makes DSM demand an `X-SYNO-TOKEN` header on
  every later call, and without it everything fails with error 119 "session expired".
* Item listings are paginated and report no total, so paging stops on an empty page. The listing
  is sorted by capture time, which lets `items_since` stop early instead of walking the library.

## Tests

The selection rules are covered by unit tests that need no NAS:

```bash
python3 -m unittest discover -s server/curator -p 'test_*.py'
```

They cover one-photo-per-day, never reusing a photo, walking past an empty day, walking past a
day whose photos were all used, and refusing to take two photos from a single day.
