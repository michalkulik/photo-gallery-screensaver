# Public access for the TV app

How to reach the NAS from outside the LAN at `synoscreensaver.mkulik.eu` **without exposing the
DiskStation**. The TV app needs exactly six WebAPI calls, so the proxy allows those and refuses
everything else.

## What is exposed

The app calls these, and nothing more:

| API | Used for |
|---|---|
| `SYNO.API.Info` | API discovery (public, needs no login) |
| `SYNO.API.Encryption` | the NAS public key |
| `SYNO.API.Auth` | sign in / sign out |
| `SYNO.Foto.Browse.Album` | the album list |
| `SYNO.Foto.Browse.Item` | the photos in an album |
| `SYNO.Foto.Download` | the image bytes |

Everything else is refused: the DSM web UI, the Photos web UI, File Station, the package centre,
user management, album mutations, and any other API. A stolen session id therefore cannot delete
anything, browse the filesystem, or change settings — it can read photos and nothing else.

## 1. Reverse proxy in Nginx Proxy Manager

Create a **Proxy Host**:

| Field | Value |
|---|---|
| Domain Names | `synoscreensaver.mkulik.eu` |
| Scheme | `http` |
| Forward Hostname / IP | `192.168.69.157` |
| Forward Port | `5001` |
| Block Common Exploits | on |
| Websockets Support | off |
| SSL | Request a new certificate, Force SSL, HTTP/2 |

Then paste [`synoscreensaver-npm-advanced.conf`](synoscreensaver-npm-advanced.conf) into the
**Advanced** tab. The upstream fields are only there because NPM requires them; the locations in
the advanced config address the NAS directly over HTTPS.

> The file is validated by `tools/test-npm-config.sh`, which composes the same server block NPM
> generates, checks it with `nginx -t`, and then sends 22 requests through a real nginx to confirm
> what is allowed and what is refused. Run it after editing the config.

## 2. Whitelist the proxy in DSM Auto Block — do not skip this

**This is the step that will otherwise break the setup.** DSM's Auto Block bans an IP after a few
failed sign-ins. Through the proxy, every request appears to come from the nginx host, so a single
brute-force attempt from the internet would ban `192.168.69.11` and lock out your own TV.

In DSM: **Control Panel → Security → Auto Block → Allow / Block List**, add `192.168.69.11` to the
allow list.

You are trading one protection for another here, which is why the next step matters.

## 3. Rate limit the sign-in endpoint

With Auto Block bypassed, the login endpoint needs another guard. In **Cloudflare → Security →
WAF → Rate limiting rules**, add a rule:

* **When incoming requests match:** `http.request.uri.path eq "/webapi/entry.cgi" and http.request.uri.query contains "SYNO.API.Auth"`
* **Rate:** 10 requests per minute per IP
* **Action:** Block for 1 hour

The TV signs in once and then reuses its session for months, so this cannot affect normal use.

## 4. Cloudflare settings that will break the TV

The TV has no browser, so anything that expects one fails:

* **Do not enable "Under Attack Mode"** for this hostname. The challenge cannot be solved and the
  app will simply see errors.
* Add a **Configuration Rule** (or WAF skip rule) for `http.request.uri.path starts_with "/webapi/"`
  that disables **Browser Integrity Check** and **Security Level**. These challenge non-browser
  clients by design.
* Keep the record **proxied** (orange cloud) so the origin IP stays hidden and the rate limit
  applies. `entry.cgi` and `query.cgi` are not in Cloudflare's default cache list, and the config
  adds `Cache-Control: no-store`, so nothing is cached.
* Cloudflare's free plan times out proxied requests after 100 seconds. A photo is a few megabytes,
  so this is comfortable, but it is why `proxy_read_timeout` is set to 120s on the origin.

## 5. Configure the app

In the TV app → **Synology NAS**:

| Field | Value |
|---|---|
| Address | `synoscreensaver.mkulik.eu` |
| Port | `443` |
| Use HTTPS | on |
| Ignore certificate errors | **off** |

Leave "Ignore certificate errors" **off** here: the public hostname has a valid Let's Encrypt
certificate, so the connection is properly verified. That toggle exists only for reaching the NAS
directly by IP inside the LAN.

Then **Connect and list albums**, enter the two-factor code once, and the device token takes over.

The port is omitted from the URL when it is the default for the scheme, so the app talks to
`https://synoscreensaver.mkulik.eu`.

## Why not expose port 5001 directly?

Because that publishes the whole DSM. Anyone reaching it gets the login page, the Photos UI, File
Station, and every API — including the ones that delete things. The proxy reduces that to six
read-only calls. It also means the NAS certificate, which is issued for a hostname while you
connect by IP, never has to be trusted blindly.

## Alternative: proxy through the existing relay

The host already runs a service that can reach the NAS (`screensaver.mkulik.eu`, port 9037). Adding
the Synology calls there would keep the NAS entirely off the internet, with the allowlist enforced
in Python rather than nginx. That is strictly more secure, at the cost of another hop and some
code. The nginx approach above was chosen because it needs no new code and fits the setup that is
already in place.
