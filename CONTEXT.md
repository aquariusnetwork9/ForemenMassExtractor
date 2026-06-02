# AutoMiner — Project Context & Handoff

> Read this fully before editing. It captures the root-cause analysis, the required
> Baritone patch, the addon design, the build config that currently works, and the
> remaining tasks. Do not "simplify" away the core decisions below — they exist for
> specific reasons documented here.

---

## 1. Goal

A Meteor Client addon (`AutoMiner`) that runs **AFK for hours** on the anarchy server
6b6t.org and mines an **enormous quantity of a single abundant block** (deepslate is
the canonical target; stone/cobbled-deepslate similar). Target volume is huge — think
thousands of large-chest loads — so it MUST run unattended without locking up.

Requirements:
- Mine one (or a few) configured target block type(s); leave everything else.
- Auto-manage storage: when inventory fills, place an ender chest, pull an empty
  shulker from it, fill the shulker with the target block, recover it, store it back in
  the ender chest, restock the pickaxe, recover the ender chest, resume. Repeat until
  the ender chest runs out of empty shulkers.
- Never stall. Any unexpected state → safe pause, not a hang.

---

## 2. THE CORE PROBLEM (why naive approaches fail)

Existing addons (e.g. dekrom's BepHax `BepMine`) are **speedmine** helpers — they make
breaking the currently-aimed block faster. They do NOT do area target selection; that
job falls to **Baritone's `mine` command**. BepHax runs on **stock** Baritone.

Stock Baritone's `MineProcess` is built for **sparse** targets (diamonds, ancient
debris). When you `mine deepslate`, this happens:

1. `MineProcess.searchWorld(...)` calls `WorldScanner.scanChunkRadius(...)` to find
   matching blocks across loaded/cached chunks.
2. Deepslate is everywhere — a single chunk has thousands of matches. The scan returns
   tens of thousands of block positions, which **flood the location cache**.
3. `MineProcess` builds a `GoalComposite` from those positions. `GoalComposite.heuristic()`
   loops over every sub-goal on **every A\* node expansion**, so per-node cost scales
   with the number of known targets.
4. A\* thrashes, the cache keeps getting re-flooded on rescans, and the bot stalls /
   has to be reset every ~15 minutes. **This is the exact symptom the user reported.**

Tuning `maxOreLocationsCount` lower just makes Baritone short-sighted (it forgets
targets and wanders); it does not fix the flooding.

**Conclusion reached with the user:** the fix belongs in **Baritone**, not the addon.
The addon cannot work around a pathfinder that is drowning in its own target cache.

---

## 3. THE BARITONE PATCH (required for correct runtime behavior)

We patch Baritone to **suppress the far-chunk scan once enough local targets are
already known**, so the cache can't be flooded by an abundant block. When local known
targets drop below a threshold, the scan re-enables.

### 3a. Add two settings — `src/api/java/baritone/api/Settings.java`
```java
public final Setting<Boolean> mineLocalChunksOnly = new Setting<>(false);
public final Setting<Integer> mineLocalChunksThreshold = new Setting<>(8);
```

### 3b. Gate the far scan — `src/main/java/baritone/process/MineProcess.java`
In `searchWorld(...)`, before the `scanChunkRadius` call that extends the cache, add a
suppression condition based on how many targets are already known locally (`locs`):
```java
boolean suppressFarScan = Baritone.settings().mineLocalChunksOnly.value
        && locs.size() >= Baritone.settings().mineLocalChunksThreshold.value;

if (!untracked.isEmpty() || (!suppressFarScan
        && Baritone.settings().extendCacheOnThreshold.value && locs.size() < max)) {
    locs.addAll(BaritoneAPI.getProvider().getWorldScanner()
        .scanChunkRadius(ctx.getBaritone().getPlayerContext(), filter, max,
                          10, /*scan radius*/ 32));
}
```
> NOTE: the exact field names around the existing scan block (`untracked`, `locs`,
> `max`, `filter`, the `scanChunkRadius` signature) must be matched to the Baritone
> source you fork. Treat the snippet as the intent, not a literal patch — read the real
> `searchWorld` and apply the `suppressFarScan` gate to the existing scan call.

### 3c. Build & install
- Fork `cabaletta/baritone`, branch `1.21.4`. Build the **unoptimized Fabric** variant
  (release jars are proguarded/obfuscated — do NOT try to mixin into those).
- Put the patched Baritone jar in the Prism instance's `mods/` folder. It overrides the
  Baritone that Meteor bundles.

### 3d. How the addon drives the patch
The addon does NOT compile against these new fields (it builds against stock
`baritone-api`). It sets them **by name at runtime** via `Settings.byLowerName`:
`minelocalchunksonly = true`, `minelocalchunksthreshold = <setting>`. If the patched
Baritone isn't installed, those lookups return null and the addon logs a warning — it
will run but mining will flood as before. **The patched Baritone is required for the
actual fix.**

> Alternative considered and REJECTED: shipping the patch as a Mixin inside the addon
> against stock Baritone. Rejected because distributed Baritone jars are obfuscated, so
> a mixin target (`MineProcess#searchWorld`) is unreliable. Forking the source and
> building the unobfuscated jar keeps symbol names intact. Keep the fork approach.

---

## 4. ADDON ARCHITECTURE (`AutoMiner.java`)

A flat finite-state machine ticked on `TickEvent.Pre`. States:

```
MINING        -> Baritone mines target (mine(0, blocks) = infinite). Drops junk.
                 When occupiedSlots >= triggerFullSlots -> PLACE_ECHEST.
PLACE_ECHEST  -> place ender chest on a valid adjacent spot.
ECHEST_TAKE   -> open echest; restock pickaxe if low; pull ONE empty shulker.
                 If no empty shulkers remain -> DONE.
PLACE_SHULKER -> place the empty shulker.
SHULKER_FILL  -> shift target stacks from player inv into the shulker (one per tick).
BREAK_SHULKER -> break the filled shulker to pick it up.
ECHEST_STORE  -> reopen echest; push filled shulker(s) in. Loop another shulker if inv
                 still has targets; else -> BREAK_ECHEST.
BREAK_ECHEST  -> break echest to recover it; -> resume MINING.
PAUSED        -> hazard (lava adjacent / nearby player) or missing resource. Auto-resume.
DONE          -> echest has no empty shulkers left; toggle module off.
```

Design principles (keep these):
- EVERY packet-generating action (drop, hotbar swap, block place, container open/close,
  one slot shift-move, block break) is on its OWN tick followed by an `actionDelay` wait —
  nothing bursts multiple packets in a single tick. The FSM uses a `step` sub-counter so
  compound operations (place = select→place→swapBack; echest-take = open→wait→restock→
  pull→close) are split across ticks. Container opens wait (poll up to 20 attempts) for the
  server to confirm the screen before acting. This is the desync guarantee for high-ping
  anarchy servers (2b2t). `action-delay` default is 15, range 1–60. Do NOT recombine
  actions into one tick.
- Every "missing resource / unexpected" branch goes to PAUSED with a timer, never a
  hang. This is the anti-stall guarantee.
- Mining itself is delegated to the **patched** Baritone so the flooding can't recur.

Key settings: `target-blocks`, `local-chunks-threshold` (pushed to Baritone),
`trigger-full-slots`, `drop-junk`, `min-pickaxe-durability`, `pause-on-hazard`,
`player-pause-range`, `action-delay`.

---

## 5. BUILD CONFIG — Minecraft 1.21.4, Yarn mappings (this is the working state)

Environment: Prism Launcher, **MC 1.21.4**, Fabric, Windows (PowerShell — use `;` or
one-command-per-line, NOT `&&`).

The project is a **Groovy** Loom build mirroring the official `meteor-addon-template` at
its 1.21.4 commit (`a1d9cc12` "Update to 1.21.4"). Do NOT reintroduce the Kotlin DSL /
version catalog / `minecraft=26.1.2` setup — that targeted a *newer* Minecraft than 1.21.4
(see §5b) and is the wrong runtime for 6b6t.

`gradle.properties`:
```
minecraft_version=1.21.4
yarn_mappings=1.21.4+build.8
loader_version=0.16.9
# Gradle 8.10 cannot run on the system JDK 24; pin the Gradle runtime to JDK 21:
org.gradle.java.home=C:/Program Files/Java/jdk-21
```

`build.gradle` key dependency lines (standard Loom remap flow):
```groovy
minecraft "com.mojang:minecraft:${project.minecraft_version}"
mappings   "net.fabricmc:yarn:${project.yarn_mappings}:v2"
modImplementation "net.fabricmc:fabric-loader:${project.loader_version}"
modImplementation "meteordevelopment:meteor-client:${project.minecraft_version}-SNAPSHOT"
modCompileOnly    "meteordevelopment:baritone:${project.minecraft_version}-SNAPSHOT"
```
- Loom plugin: `fabric-loom version "1.8-SNAPSHOT"` (resolves to 1.8.13). Gradle wrapper:
  **8.10**. Java release: **21**.
- `meteor-client:1.21.4-SNAPSHOT` and `baritone:1.21.4-SNAPSHOT` are published in
  **intermediary** bytecode; Loom **remaps them to Yarn** (`modImplementation`/`modCompileOnly`),
  and `remapJar` remaps OUR output back to intermediary for distribution.
- Baritone is `modCompileOnly` (remapped, compile-only, not bundled — the installed patched
  Baritone provides it at runtime). Do NOT use a local `files("libs/...jar")` jar.

Build: `./gradlew build` (uses pinned JDK 21 automatically) → distributable, intermediary-
remapped jar at `build/libs/addon-template-0.1.0.jar`. Drop that + the patched Baritone into
Prism's `mods/` alongside Meteor.

---

## 5b. VERSION-SCHEME TRAP — why this is NOT `minecraft=26.1.2`

A prior commit set `minecraft=26.1.2` believing it equalled "1.21.4". It does NOT.
`com.mojang:minecraft:26.1.2` resolves to a **2026 Minecraft, newer than 1.21.4**, with
renamed Mojang symbols (`handleContainerInput`/`ContainerInput`, `ItemContainerContents
.nonEmptyItemCopyStream()`, `Identifier`, `ItemStackTemplate`). Meteor's "26.x" is its own
version label for that MC, built with **Mojang** mappings + a non-remapping Loom 1.16 that
ships NAMED jars. That whole stack is incompatible with a 1.21.4 anarchy server. This project
is deliberately pinned to **real MC 1.21.4 + Yarn** instead.

- AutoMiner.java is in **Yarn 1.21.4** names (`MinecraftClient`/`mc.world`,
  `currentScreenHandler`, `interactionManager.clickSlot(..., SlotActionType.QUICK_MOVE, ...)`,
  `getMainHandStack`, `getBlockPos`, `Vec3d`, `Hand`, `DataComponentTypes`, `Registries.BLOCK
  .getId(...)`). `MiningToolItem` does NOT exist in 1.21.4 — detect tools via
  `stack.contains(DataComponentTypes.TOOL)`.
- Mining is driven via `getMineProcess().mineByName(0, String...)` (registry-name strings),
  which is mapping-agnostic and matches the "drive Baritone by name" design.
- Build must run on **JDK 21** (pinned via `org.gradle.java.home`). The system default JDK
  here is 24, which Gradle 8.10 rejects. If you ever see "Unsupported class file major
  version" at Gradle startup, that pin was lost.

## 6. CURRENT STATUS / REMAINING WORK

DONE:
- Whole stack pinned to real **MC 1.21.4** (minecraft, yarn, meteor-client, baritone,
  fabric-loader 0.16.9, Loom 1.8.13, Gradle 8.10, Java 21).
- `AutoMiner.java` in Yarn 1.21.4. Template example cruft (CommandExample / ModuleExample /
  HudExample / ExampleMixin) deleted; AutoMiner is the sole module, registered in
  `AddonTemplate.onInitialize()` under the "Mining" category.
- `./gradlew build` is **BUILD SUCCESSFUL**; `remapJar` produces an **intermediary** jar at
  `build/libs/addon-template-0.1.0.jar` (verified: `class_1657`… refs, no leftover Yarn names)
  with `fabric.mod.json` depends `minecraft ["1.21.4"]`, `java >=21`.

PATCHED BARITONE — BUILT (2026-05-31):
- Source: `MeteorDevelopment/baritone` cloned at **`C:\Users\hellj\baritone`**, branch `1.21.4`.
  Patch applied to two files:
  - `src/api/java/baritone/api/Settings.java`: added `mineLocalChunksOnly` (default false) +
    `mineLocalChunksThreshold` (default 8).
  - `src/main/java/baritone/process/MineProcess.java` `searchWorld(...)`: added a
    `suppressFarScan` gate before the `scanChunkRadius` call —
    `mineLocalChunksOnly && (locs.size() + alreadyKnown.size()) >= mineLocalChunksThreshold`.
    (Deepslate is NOT a cache-tracked block, so it always hit the `!untracked.isEmpty()`
    branch → the far scan ran every rescan → flood. The gate stops that once enough targets
    are known, and re-enables when they deplete.)
- Build (unobfuscated, NOT the proguarded `dist/` jar): from the baritone dir, with JDK 21,
  `./gradlew :fabric:remapJar`. Output: **`fabric/build/libs/baritone-fabric-1.21.4-SNAPSHOT.jar`**
  (1.78 MB). Verified: `Settings.class` + `MineProcess.class` contain the new field names; mod
  id is **`baritone-meteor`** (so it REPLACES the user's existing Baritone, not added alongside).
- Note: Baritone's Gradle is 8.7 and needs JDK 21 to run — set `JAVA_HOME` to jdk-21 for that build.

REMAINING (runtime validation — cannot be done from the build alone):
- Install in Prism `mods/`: remove the old `baritone-meteor` jar, add the patched one + Meteor
  + `auto-miner-0.1.0.jar`. Confirm AutoMiner appears under the "Mining" category and toggles
  without the "Baritone setting not found" warning (that warning = patched Baritone not loaded).
- Confirm deepslate mining runs >15 min without stalling.
- Test a full storage cycle end-to-end (echest place → shulker fill → store → resume).

Slot-indexing assumptions still worth a runtime sanity check (compile-correct, not yet
runtime-verified): player inventory is the trailing 36 slots of `ScreenHandler.slots` for
both the ender chest (GenericContainerScreenHandler) and the shulker (ShulkerBoxScreenHandler).

FUTURE / OPTIONAL (not required for v1):
- Deterministic coverage (spiral / chunk-by-chunk) via a moving Baritone selection
  (`getSelectionManager()`), advancing the cuboid per cleared chunk. Current behavior is
  "infinite nearest target," which the patch makes stable but is not strictly ordered.

---

## 7. HARD CONSTRAINTS — do not change without good reason
- Keep the Baritone FORK approach for the scan patch (not a mixin into obfuscated jars).
- Keep Baritone as Loom-remapped `meteordevelopment:baritone` (not a local jar).
- Keep the FSM's "unexpected -> PAUSED" anti-stall guarantee.
- Keep one-container-action-per-`actionDelay`-ticks pacing.
- The patched Baritone is REQUIRED for the mining fix; the addon alone does not solve
  the cache-flood problem.
