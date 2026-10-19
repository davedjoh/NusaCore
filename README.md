# NusaCore

NusaCore is a client and server Fabric mod for Minecraft 26.2. Players install the modpack. The server is authoritative; future clients render approved state and send validated requests.

Implemented: the bootstrap lifecycle, the first gameplay slice `/lobby`, and the claim storage foundation (SQLite repository plus an in-memory committed-claim cache). Not implemented: claim commands (`/claim`, `/unclaim`), claim protection, ownership transfer, currency, guilds, quests, GUI, HUD, expiry policy, packets, and handshake policy.

## `/lobby`

`/lobby` teleports the executing player to a safe standing position at the Overworld world spawn, from any dimension.

- Spawn source: the Overworld's own world spawn, read live from level data on every call, so vanilla `/setworldspawn` stays the only way to move it. No coordinates are copied into a config and no database is involved.
- Destination: the spawn column is scanned from the spawn feet block downwards, nearest column first, inside a bounded area. A position is accepted when the ground is a full cube that is not liquid or a hazard and the two blocks of the player body are free. Hazards include blocks that damage without a collision shape, such as a wither rose, fire, and portals, so a position cannot be picked that would burn or teleport the player. Unloaded chunks are skipped, so the command never forces chunk generation and does no disk or database work.
- Failure: a missing Overworld or no safe position within the search bounds reports a message and leaves the player where they were.
- Respawn: vanilla first join and bed or anchor respawn behavior is untouched. `/lobby` never writes respawn data.
- `/lobby` is registered without arguments. If another mod already registers `/lobby`, NusaCore does not replace it. There is no `/spawn` alias and no `/setspawn` command.

### Countdown

A request does not teleport immediately. The server starts a 5-second countdown (`Messages.LOBBY_COUNTDOWN_SECONDS`) and moves the player only when it finishes.

- Timing: one step per 20 server ticks on the server tick loop. Nothing sleeps, blocks, starts a thread, or touches disk. The countdown length is a constant; moving it to config is future work.
- Feedback: a start message with the full number, then one message per remaining second (`5, 4, 3, 2, 1`), then the success line. Each step plays the vanilla `ui.button.click` sound privately to that player on the UI channel, with the pitch rising each second; the successful teleport plays `ui.toast.challenge_complete`. Other players hear nothing.
- Duplicate requests: one countdown per player. A second `/lobby` while one is pending answers with a message and does not restart, extend, or queue it.
- Cancellation: moving, dying, changing dimension, losing the permission node, disconnecting, or the server stopping ends the countdown with a message instead of a teleport. Looking around is not movement. A cancelled countdown cannot be resumed.
- Authority: the permission node, player liveness, dimension, and the destination are re-checked by the server during the countdown. The destination is resolved once when the request starts, and the teleport uses the same level the safe position was read from.
- Failure: if the server refuses the move at the last second, the player is told and stays where they were; no completion sound plays.
- Cleanup: countdowns are in memory only. A disconnect or server stop removes the pending entry, so a restarted server never resumes one.

### Permission

The command requires the LuckPerms node `nusacore.spawn.lobby`, for example:

```
lp group default permission set nusacore.spawn.lobby true
```

The gate denies when the permission provider returns no value, so nothing is granted by default, operators have no implicit bypass, and a server without LuckPerms keeps the command locked. A denied player receives a message; console and command blocks get a player-only message.

### Search bounds

`core/config/SpawnConfig` holds the limits as in-memory defaults: a horizontal radius of 8 columns and a scan depth of 5 blocks per column. Loading these from a config file is future work.

## Claim storage foundation

This slice makes claims durable and readable. It adds no command, no protection, no GUI, and no networking, so nothing a player can do yet creates a claim.

- Database: SQLite via `org.xerial:sqlite-jdbc`, behind `core/storage/ClaimRepository`. That interface is the only claim-storage type the rest of the mod sees; SQL, JDBC, and the driver stay inside `core/storage/sqlite`, so a different backend can replace it without touching `core/claim`.
- File: `<world>/data/nusacore-claims.db`, created with its directory on first start. WAL, `synchronous=FULL`, `foreign_keys=ON`, a 5 s busy timeout, and `IMMEDIATE` write transactions.
- Schema: versioned in `PRAGMA user_version` (currently 1). Migrations are an ordered, grow-only list; each version is applied in one transaction together with its version bump, so a crash cannot leave a half-migrated database. A database newer than the build is refused at startup instead of being written to.
- Claim identity: `(dimension, chunk_x, chunk_z)` under a unique index. The dimension is stored as its namespaced identifier, never folded into a packed chunk coordinate, so the same chunk in the Overworld and the Nether are two claims.
- Model: owner is `player` with a UUID or `admin` with none, enforced by a `CHECK` constraint; trusted players live in their own table with per-player permissions, so trust rows are canonical and cascade with their claim; timestamps are fixed-width UTC ISO-8601 with nanosecond precision, so text order is chronological.
- Expiry: `expires_at` is nullable and part of the model from the start, but no duration, grace period, or release behavior is decided. Nothing creates an expiring claim today, and a stored expiry earlier than the creation time is rejected.
- Cache: `core/cache/ClaimCache` holds committed claims in memory so reads never touch disk. It is not ready until the initial load finishes, and `ready()` lets a caller tell "unclaimed" from "not loaded".
- Ordering: `core/claim/ClaimStore` is the only path from storage to cache. A mutation is committed first; only a successful commit activates the cache and completes the caller's result, on the server thread. A duplicate key, a missing claim, or a failed write leaves the cache exactly as it was. There is deliberately no method that writes the cache before the commit.
- Failures: a duplicate key, a missing claim, an unreadable row, and a closed repository are result values, not exceptions. An unreadable row does not fail the load; it is listed in the snapshot and logged at startup.
- Threading: all database work runs on one daemon worker named `nusacore-claim-storage`, and every repository call returns immediately. Opened on `SERVER_STARTING` and closed on `SERVER_STOPPING`, because the world path is only known once a server exists. The start callback returns before the directory, connection, schema, and initial load finish; `ClaimStorageLifecycle#ready()` is the gameplay gate.
- Shutdown: `close()` drains every accepted operation before it returns. If the worker does not finish within 10 s, it is interrupted, queued operations are answered `CLOSED` before they can start, and an operation that was already running may still commit after its caller was told `CLOSED`. That last case is the only unavoidable durability limit; no future is left incomplete.

Claim commands, protection rules, expiry policy, currency, GUI, and networking are later slices and read through `ClaimStore#findCached`.

## Build

Windows PowerShell:

Install a Java 25 JDK and set `JAVA_HOME` to its installation directory (the directory containing `bin/java.exe`). Java is not installed system-wide by this project. Then run from `D:\\mods\\NusaCore`:

```powershell
.\gradlew.bat clean build --no-daemon
```

Pinned build inputs are Java 25, Gradle 9.7.1, Fabric Loom 1.18.4, Fabric Loader 0.19.5, Fabric API 0.161.0+26.2, and SQLite JDBC 3.53.4.0. The artifact is `build/libs/nusacore-0.1.0.jar`. Install it on client and server with Fabric API. Fabric Permissions API 0.7.0 and SQLite JDBC 3.53.4.0 are bundled and declared as nested jars. LuckPerms is an optional server runtime provider. NusaCore does not require Voxy or VoxyServer and does not link their classes.

## Compatibility evidence

See [`docs/compatibility.md`](docs/compatibility.md) for pinned coordinates, official source URLs, published metadata, the published-versus-runtime-tested distinction, jar inspection, the bounded server smoke result, and which `/lobby` behavior is tested versus not verified.

## Scope decisions

The source of truth is [`../context.md`](../context.md). Key decisions are SQLite behind a repository boundary for future persistent state, free initial claims, owner-only claim management, offline trust changes, ownership transfer, commit-before-cache mutation, and vanilla world spawn as the lobby source. Claim expiry includes UTC `expires_at` from the initial model, but policy is still open. Networking and client UI are later slices after quest; no chest-style GUI is planned.

No EULA was accepted, so no world was generated in the smoke run. The server smoke reached the normal EULA gate and logged the NusaCore initializer line before stopping. `/lobby` has not been executed in a running server by a real player, no LuckPerms grant was tested, and client runtime launch and connections are not verified. Claim storage has not been exercised against a live server world: the repository, schema, cache, and lifecycle path are covered by tests on temporary database files, not by a running server creating or reading a claim database under a world directory. Generated build, run, and Gradle cache directories are ignored.

