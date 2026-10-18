# NusaCore
<<<<<<< HEAD

NusaCore is a client and server Fabric mod for Minecraft 26.2. Players install the modpack. The server is authoritative; future clients render approved state and send validated requests.

Implemented: the bootstrap lifecycle and the first gameplay slice, `/lobby`. Not implemented: claims, currency, guilds, quests, protection, GUI, HUD, expiry, packets, and handshake policy.

## `/lobby`

`/lobby` teleports the executing player to a safe standing position at the Overworld world spawn, from any dimension.

- Spawn source: the Overworld's own world spawn, read live from level data on every call, so vanilla `/setworldspawn` stays the only way to move it. No coordinates are copied into a config and no database is involved.
- Destination: the spawn column is scanned from the spawn feet block downwards, nearest column first, inside a bounded area. A position is accepted when the ground is a full cube that is not liquid or a hazard and the two blocks of the player body are free. Hazards include blocks that damage without a collision shape, such as a wither rose, fire, and portals, so a position cannot be picked that would burn or teleport the player. Unloaded chunks are skipped, so the command never forces chunk generation and does no disk or database work.
- Failure: a missing Overworld or no safe position within the search bounds reports a message and leaves the player where they were.
- Respawn: vanilla first join and bed or anchor respawn behavior is untouched. `/lobby` never writes respawn data.
- `/lobby` is registered without arguments. If another mod already registers `/lobby`, NusaCore does not replace it. There is no `/spawn` alias and no `/setspawn` command.

### Permission

The command requires the LuckPerms node `nusacore.spawn.lobby`, for example:

```
lp group default permission set nusacore.spawn.lobby true
```

The gate denies when the permission provider returns no value, so nothing is granted by default, operators have no implicit bypass, and a server without LuckPerms keeps the command locked. A denied player receives a message; console and command blocks get a player-only message.

### Search bounds

`core/config/SpawnConfig` holds the limits as in-memory defaults: a horizontal radius of 8 columns and a scan depth of 5 blocks per column. Loading these from a config file is future work.

## Build

Windows PowerShell:

Install a Java 25 JDK and set `JAVA_HOME` to its installation directory (the directory containing `bin/java.exe`). Java is not installed system-wide by this project. Then run from `D:\mods\NusaCore`:

```powershell
.\gradlew.bat clean build --no-daemon
```

Pinned build inputs are Java 25, Gradle 9.7.1, Fabric Loom 1.18.4, Fabric Loader 0.19.5, and Fabric API 0.161.0+26.2. The artifact is `build/libs/nusacore-0.1.0.jar`. Install it on client and server with Fabric API. Fabric Permissions API 0.7.0 is bundled and declared as a dependency because the permission nodes call it directly. LuckPerms is an optional server runtime provider. NusaCore does not require Voxy or VoxyServer and does not link their classes.

## Compatibility evidence

See [`docs/compatibility.md`](docs/compatibility.md) for pinned coordinates, official source URLs, published metadata, the published-versus-runtime-tested distinction, jar inspection, the bounded server smoke result, and which `/lobby` behavior is tested versus not verified.

## Scope decisions

The source of truth is [`../context.md`](../context.md). Key decisions are SQLite behind a repository boundary for future persistent state, free initial claims, owner-only claim management, offline trust changes, ownership transfer, commit-before-cache mutation, and vanilla world spawn as the lobby source. Claim expiry includes UTC `expires_at` from the initial model, but policy is still open. Networking and client UI are later slices after quest; no chest-style GUI is planned.

No EULA was accepted, so no world was generated in the smoke run. The server smoke reached the normal EULA gate and logged the NusaCore initializer line before stopping. `/lobby` has not been executed in a running server by a real player, no LuckPerms grant was tested, and client runtime launch and connections are not verified. Generated build, run, and Gradle cache directories are ignored.
=======
NusaCore is custom mod for my minecraft server
>>>>>>> e196d15a969ba53b6a97b16f8798a24b6071f503
