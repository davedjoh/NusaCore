# Compatibility record

Recorded 2026-10-18. The bootstrap checks and the `/lobby` slice checks are separate entries below. These checks establish published metadata, unit behavior, and a local loader smoke, not a guarantee for every modpack or a long-running production server.

## Pins

| Component | Pin | Evidence |
|---|---|---|
| Minecraft | 26.2 | Fabric example mod `26.2` branch and generated server launch |
| Java | 25 | Official Fabric 26.2 example uses release 25; local Temurin 25.0.4.1+1 was used for the revised build/smoke |
| Fabric Loader | 0.19.5 | Fabric Meta loader endpoint for `26.2` |
| Fabric API | 0.161.0+26.2 | Modrinth API version `ewUK83HI`, release, Fabric, 26.2 |
| Fabric Loom | 1.18.4 | Fabric Maven metadata and successful Gradle configuration |
| Gradle wrapper | 9.7.1 | Official Gradle distribution, SHA-256 pinned in wrapper properties |
| Fabric Permissions API | 0.7.0 | Modrinth version `b1EqjxFs`, server-only, 26.2; included in NusaCore as `fabric-permissions-api-v0` |
| LuckPerms | 5.5.85 Fabric | Modrinth version `DzQPkkXY`, server-only, 26.2; its metadata requires Fabric Permissions API v0 >= 0.7.0 |
| VoxyServer | 1.2.4 | Official Modrinth version `EsIPjK0A`, listed as release for Fabric and Minecraft 26.2; published metadata reports `client_and_server` environment |
| Voxy | 0.2.19-beta | Modrinth version `LzyXnE51`, client and server environment, 26.2; required by VoxyServer metadata |
| Sodium provider | 0.9.2-alpha.4 | Nested `sodiumprovider` metadata inside the Voxy jar, provides `sodium` for server startup |

## Official and primary sources

- Fabric 26.2 example: https://github.com/FabricMC/fabric-example-mod/tree/26.2
- Fabric Meta loader versions: https://meta.fabricmc.net/v2/versions/loader/26.2
- Fabric Permissions API usage and version matrix: https://github.com/lucko/fabric-permissions-api/blob/master/USAGE.md
- Fabric API Modrinth version API: https://api.modrinth.com/v2/project/fabric-api/version/ewUK83HI
- LuckPerms Modrinth version API: https://api.modrinth.com/v2/version/DzQPkkXY
- VoxyServer Modrinth project and version API: https://api.modrinth.com/v2/project/voxyserver and https://api.modrinth.com/v2/version/EsIPjK0A
- Voxy Modrinth version API: https://api.modrinth.com/v2/version/LzyXnE51
- VoxyServer source: https://github.com/poosmacker/voxyserver
- Gradle distribution checksum: https://services.gradle.org/distributions/gradle-9.7.1-bin.zip.sha256

## Dependency interpretation

VoxyServer's Modrinth listing has an empty dependency array, but its published `fabric.mod.json` is authoritative for loader resolution and declares `voxy: "*"`, `fabric-api: "*"`, Fabric Loader >= 0.19.3, and Minecraft `~26.2`. Voxy's metadata requires Fabric API >= 0.91.1, Minecraft exactly 26.2, and Sodium 0.9.1 or 0.9.2. The selected Voxy jar contains a nested server `sodiumprovider` which provides Sodium 0.9.2-alpha.4. This is why the smoke set includes Voxy and VoxyServer, while NusaCore itself does not depend on either.

LuckPerms 5.5.85 Fabric declares Minecraft >= 26.1, Fabric API modules, and bundled `fabric-permissions-api-v0` >= 0.7.0. NusaCore declares `fabric-permissions-api-v0: "*"` as a dependency because `core/permission/PermissionNodes` calls the API directly. The API is also included as a nested jar, so the tree still loads when LuckPerms bundles its own providers. LuckPerms remains an optional server provider: with no provider installed, every NusaCore check falls back to denied, and no command is granted implicitly.

The official Modrinth API response for Voxy Server 1.2.4 records published compatibility only. It does not make Voxy a NusaCore dependency and does not substitute for testing the exact target server image.

## Observed checks

- `gradlew.bat clean build --no-daemon`: PASS (bootstrap slice). Java compilation, resource expansion, jar, and JUnit metadata tests passed.
- Bootstrap artifact inspection test: PASS. `fabric.mod.json` used environment `*`, dedicated server and client entrypoints, no Voxy/Sodium dependency, and the permissions API nested jar. Both entrypoint classes were packaged. No mixins or Voxy/Sodium classes were found.
- Bootstrap dependency smoke: PASS through mod initialization. With LuckPerms 5.5.85, Voxy 0.2.19-beta, VoxyServer 1.2.4, Fabric API 0.161.0+26.2, and NusaCore, Fabric Loader 0.19.5 loaded 61 mods; LuckPerms, Voxy, VoxyServer, and NusaCore initialized without a linkage or dependency-resolution failure.
- Bootstrap smoke: `gradlew.bat runServer --no-daemon` reached NusaCore initialization and the EULA gate, but the process did not exit before the 180-second tool timeout. The generated `run/eula.txt` remains `eula=false`; no EULA was accepted. Voxy emitted missing optional Chunky mixin warnings and continued initialization.
- `/lobby` slice, build and tests: PASS. `gradlew.bat clean build --no-daemon` passed with 54 JUnit tests in 9 classes and no failures. Coverage is the packaged metadata and entrypoints, permission fallback and provider answers, message mapping, the pure destination rules, the lobby outcome logic, command registration in a real Brigadier dispatcher, the request path itself (non-player, provider grant and denial, no-provider fallback, permitted, unsafe, unloaded, and missing Overworld), and the hazard classification against the real 26.2 block registry.
- `/lobby` slice, hazard classification: PASS. A wither rose was missing from the hazard list. It has no collision shape, so the shape and fluid rules treated it as open air and a player could be placed inside its damage. `LevelSpawnSurface.isHazard` now includes `WITHER_ROSE`, `END_GATEWAY`, and the lit-state checks for campfires and candles, and the audit against the 26.2 registry is pinned by `LevelSpawnSurfaceTest`. That test bootstraps `SharedConstants` and `Bootstrap` so it reads real block states, real collision shapes, and real state properties, and it proves a wither rose in the feet block or the head block rejects the column while the normal neighbouring column is accepted.
- `/lobby` slice, request path: PASS. `LobbyRequestTest` drives the production request path, including the gate wiring used by `LobbyCommand`, with a token player and an in-memory `SpawnSurface`. It covers a non-player source, a player without the node, an explicitly denied gate, an explicit grant and an explicit denial from a provider registered through `PermissionCheckEvent`, an approved player resolving the spawn and moving, a blocked spawn column moving outwards, an unsafe area, an unloaded area with no block reads, and a missing Overworld. Every failure case asserts that no move was recorded. The real `PermissionNodes.check` gate ships unchanged; only its provider is substituted, so this exercises the same code path LuckPerms drives.
- `/lobby` slice, in-game behavior: NOT VERIFIED. Executing `/lobby` in game, a persisted LuckPerms grant, the cross-dimension teleport, and the client-side dimension change were not observed. The unit tests cannot build a `ServerPlayer` or a `ServerLevel`, so `LevelLobbyTarget` (the `TeleportTransition` construction and the live `ServerLevel` view) and the `SourceActions` adapter are covered only by compilation and the server startup check.
- `/lobby` slice, server startup: PASS. `gradlew.bat runServer --no-daemon` with Fabric API 0.161.0+26.2, Fabric Permissions API 0.7.0, LuckPerms 5.5.85, Voxy, VoxyServer, and the revised jar loaded 61 mods and logged `NusaCore bootstrap initialized: /lobby enabled`, which runs after `CommandRegistrationCallback.EVENT.register` succeeds and after the `LobbyCommand` and `LobbyRequest` construction. No linkage or class-loading error from NusaCore appeared. The process then stopped at the EULA gate; no EULA was accepted and the world was not generated. This confirms the revised classes load in the loader, not that the command runs.
- `/lobby` slice, in-game behavior: NOT VERIFIED. Executing `/lobby` in game, a persisted LuckPerms grant, the cross-dimension teleport, and the client-side dimension change were not observed. The in-JVM provider tests prove the gate reacts to a provider's answer, but a real LuckPerms grant, the server-side chunk load at the destination, and the client following the dimension switch remain unverified.
- Client source compilation passed; client runtime launch, connection, missing-client enforcement, and protocol mismatch behavior are NOT VERIFIED. No payloads, GUI, HUD, or handshake are implemented. The common network package will be added with the real networking slice, not as an unused placeholder.
- Initial checks without `JAVA_HOME` failed because Java is not on PATH. Setting it to the discovered local JDK fixed that environment issue. One split-build attempt timed out at 300 seconds, and the next exposed an obsolete server-only test assertion; after correcting that assertion, `clean build --no-daemon` passed in 12 seconds, with two tests and nine executed tasks. Gradle reported deprecated features incompatible with Gradle 10; Gradle remains pinned to 9.7.1.

The split source-set configuration follows https://raw.githubusercontent.com/FabricMC/fabric-example-mod/26.2/build.gradle (verified during this revision): `splitEnvironmentSourceSets`, main/client source sets grouped as one mod, Java release 25, and no mappings dependency.

Published compatibility is not the same as runtime-tested compatibility. A later feature slice must retest with the target Crafty image, actual server configuration, and the exact client/server mod set.
