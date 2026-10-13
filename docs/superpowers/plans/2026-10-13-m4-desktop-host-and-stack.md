# Milestone 4: Desktop Seats and the Full Stack Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:**
- A desktop TripleA client (built from the submodule at the tag) joins a game with a reserved seat
  through an isolated `desktop-host` process, using a one-time password from the app.
- The whole thing runs as one `docker compose` stack with Caddy and TLS, locally and later on the
  mini PC.
- Spec success criterion: two phones (or emulators) and one desktop client play a full game
  through the local `docker compose` stack, and a server restart in the middle of a mobile game
  loses nothing.

**Architecture:**
- **New Gradle module `desktop-host`** in `triplea-mobile-server`:
  - it runs live games with desktop seats, one desktop `ServerMessenger` per game on a port from
    3300 to 3319;
  - no database, no secrets;
  - it talks to `server` over a narrow internal HTTP API with a shared token: start a game, relay
    questions for mobile seats, report the result.
- **Mobile seats in a desktop-host game** use the same `RemoteHumanUi`. Its `HostLink` relays every
  `Ask` to `server` over the internal API. `server` forwards it to the phone as usual and returns
  the answer.
- **Security** (spec section 4, "Desktop access"):
  - `-Djdk.serialFilter` allow-list;
  - a custom `ILoginValidator` with one-time passwords per reserved seat;
  - a `ServerModel` subclass that restricts `takePlayer`/`releasePlayer` and blocks
    `changeToGameSave`/`changeToGameOptions` after creation;
  - the container is read-only, non-root, with memory and CPU limits and no route to Postgres.
- **Stack:** `docker-compose.yml` with `postgres`, `server`, `desktop-host` and `caddy`.
  - Caddy uses `tls internal` locally.
  - TCP 3300–3319 is published for `desktop-host`.
  - The networks are `internal` (server–postgres, server–desktop-host) and `edge`
    (caddy–server, desktop-host ports).
- **App:**
  - the debug server URL becomes `https://10.0.2.2:8443`;
  - the debug `network_security_config` trusts the Caddy local CA through `debug-overrides`;
  - the create dialog gets a "Desktop player" seat kind;
  - the lobby shows "requires TripleA 2026-10.16" and the one-time password after confirmation.

**Spec:** `docs/superpowers/specs/2026-10-12-multiplayer-lobby-design.md`:
- section 2, `desktop-host`;
- section 3, "Desktop seats in v1";
- section 4, "Network" and "Desktop access";
- section 5;
- section 6, server test 6;
- "Open points" 3.

**Prerequisites:** milestones 3a and 3b are complete.

## Global Constraints

- The earlier milestones' constraints apply.
- `desktop-host` never gets DB credentials or user tokens. Its only secret is the internal API
  token, and that token can do nothing except the narrow API.
- **Serial filter:**
  - Allowed: the prefixes from `SafeObjectInputStream` (read the mobile file for the exact list),
    plus `java.net.InetSocketAddress`, `InetAddress`, `Inet4Address` and `Inet6Address`. Then `!*`.
  - Limits: `maxbytes=10485760`, `maxdepth=100`, `maxrefs=1000000` and `maxarray=1000000`. Tune
    them only if a real game needs more, and record the value.
  - Whether `java.awt.Color` or `Point` are needed is decided by test 6 (spec open point 3).
- **Desktop seats:** no clocks and no standing orders (spec "Desktop seats in v1").
  - A dropped desktop player triggers a save and a pause, as on the desktop
    (`ServerLauncher.connectionLost`).
  - Desktop live games are not recovered after a restart; the autosave lets players restart them.
- **Desktop versions:** no enforced version check in v1 (spec).
  - A deserialization failure disconnects the client with a clear log line.
- **Ports:** 3300–3319, at most 20 concurrent desktop games. A game that cannot get a port is
  refused with a clear error.

## Review Focus

1. **Pre-authentication deserialization.** A payload outside the allow-list must fail at the
   filter before any game code runs. Test 6 sends a crafted serialized object to the port.
2. **Seat binding.**
   - A valid desktop login for seat A cannot take seat B, or release someone else's seat.
   - A wrong or reused one-time password is refused.
3. **The internal API is not reachable from the edge network.** Check the compose networks and
   test it with `docker compose exec`.
4. **The relay keeps 2b's guarantees.** A mobile seat in a desktop-host game gets the same
   question and validation path, and no answer reaches the engine without passing
   `RemoteHumanUi`'s checks.
5. **The TLS switch must not break the debug app.** The Caddy local CA is trusted in debug only;
   release trusts system CAs only.

---

### Task 1: `desktop-host` game launch with seat binding

- Module `desktop-host`, depending on `triplea:game-core`, `ai` and `game-headless`.
- `SeatLoginValidator` implements `ILoginValidator`. It uses the username from
  `getChallengeProperties(username)` to check a one-time password per reserved seat. Passwords are
  hashed, single use, and expire after 30 minutes.
- `BoundServerModel` subclasses `ServerModel` with the restrictions above. Read the desktop
  `ServerModel`, `ServerLauncher` and `HeadlessGameServer` at the tag for the hooks.
- Launch: `ServerMessenger` on the reserved port, then the game. Mobile seats are
  `RemoteHumanUi`s with an `InternalLink` (Task 2), and AI seats run in-process.
- Tests (spec server test 6, the first three bullets):
  - a real desktop `ClientMessenger` from the submodule connects with the one-time password and
    plays a few moves (scripted);
  - a wrong password or unknown name is refused;
  - `takePlayer` on someone else's seat is refused.

### Task 2: Internal API and relay

- `server`: `POST /internal/desktop-games` (start), relay endpoints, and result reporting.
  - The token comes from env `INTERNAL_TOKEN`, compared in constant time.
  - The routes are bound only on the internal network interface/port (a separate Ktor connector
    on `INTERNAL_PORT`).
- `desktop-host`: `InternalLink : HostLink`. It long-polls or holds a WebSocket to `server` for the
  answers of its mobile seats.
- Game creation: a seat kind `DESKTOP` in `CreateGameRequest`. A game with any desktop seat starts
  in `desktop-host` instead of `server`.
- Tests: a desktop-host game with one mobile seat, played by the 2b `TestPhone` through `server`,
  and one AI seat; plus Review Focus 4.

### Task 3: Serial filter and container hardening

- The `desktop-host` JVM flags carry the serial filter from Global Constraints.
- The Dockerfile is non-root and multi-stage. The compose service is `read_only: true` with a
  tmpfs for temp and autosave, `mem_limit` and `cpus`, and no `internal` network route to Postgres.
- Test 6's last bullet: a crafted payload, such as a serialized `java.util.HashMap` holding an
  unlisted class, sent to the port before login, fails at the filter. Assert the log line and the
  closed connection.
- Settle spec open point 3: run a full desktop game and record whether `java.awt.Color` or `Point`
  had to be allowed.

### Task 4: Compose stack with Caddy

- `docker-compose.yml`: `postgres`, `server`, `desktop-host`, `caddy` with networks `internal` and
  `edge`.
- `Caddyfile`:
  - `tls internal` locally, a real certificate through `.env` on the mini PC;
  - `reverse_proxy server:8080` for HTTPS and WebSocket;
  - rate limits on `/auth/*` and the WebSocket upgrade, if the Caddy build has a rate-limit module;
    otherwise keep the server-side limits and record the ruling.
- Ports: 8443 locally, and 3300–3319 TCP straight to `desktop-host`.
- `.env.example` gets every new variable.
- App debug build:
  - the server URL default becomes `https://10.0.2.2:8443`;
  - `app/src/debug/res/xml/network_security_config.xml` `debug-overrides` trusts the Caddy root
    CA. Put the CA in `app/src/debug/res/raw/`, exported once from the local Caddy; document how
    to refresh it.
  - Release stays on system CAs.
- Device check: the app against the compose stack over HTTPS.

### Task 5: End-to-end and docs

- The spec success run:
  - two emulators and one desktop client, built from `triplea/` at the tag, play a full minimap
    game through `docker compose up --build`;
  - restart `server` in the middle of a mobile game and check nothing is lost.

  Screenshots, plus a log excerpt from each step.
- Docs:
  - the server README: stack, ports, networks, hardening, the serial-filter decision;
  - `docs/DEVELOPMENT.md`: online play and security notes;
  - `docs/online-test-checklist.md`: the desktop part.
- Deployment notes for the mini PC behind webtropia (WireGuard, VLAN, SSH key-only, admin panel
  over WireGuard only, CrowdSec or fail2ban, restic backups). Documentation only; nothing is
  deployed from this plan.
