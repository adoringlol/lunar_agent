# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this project is

A Java instrumentation agent (`-javaagent:`) for Lunar Client (a Minecraft 1.8.x client). It hooks into Minecraft internals at runtime using ASM bytecode manipulation to add a match timer HUD, TNT/fireball proximity warnings, trajectory rendering, friend notifications, and custom chat commands.

## Build commands

```bash
# Build (Linux/Mac)
./gradlew shadowJar

# Build (Windows)
build.bat
```

Output JAR: `build/libs/mcctimer-agent-1.0-SNAPSHOT.jar`

There are no tests. The `build` task depends on `shadowJar`, so `./gradlew build` also works. Source/target compatibility is Java 1.8, but the agent runs on Java 9+ (uses `MethodHandles.Lookup.defineClass`).

## Architecture

### The classloader boundary problem

The agent JAR is loaded by the **system classloader**, while Minecraft is loaded by **Lunar's custom classloader**. These two trees cannot see each other directly. This shapes the entire codebase:

- **Injected bytecode** in Minecraft classes must reach agent classes via `Class.forName("com.mcctimer.agent.X", true, ClassLoader.getSystemClassLoader())` + reflection, never direct references.
- **Agent code** reading Minecraft state uses `Thread.currentThread().getContextClassLoader()` to load Minecraft classes, then accesses all fields/methods via reflection.

### Dual MCP/Notch mapping

Lunar Client ships both MCP-mapped (readable names like `GuiIngame`, `renderGameOverlay`) and Notch-obfuscated (single-letter names like `avo`, `a`) versions of classes depending on the build. Every class/field/method lookup tries MCP first, then Notch, with a try/catch around each. The obfuscated names are for Minecraft 1.8.x and are hardcoded constants in each file.

### ASM transformation pipeline (`MCCTimerAgent.java`)

`MCCTimerAgent.premain()` registers a `ClassFileTransformer` that intercepts these Minecraft classes:

| Minecraft class | What's injected |
|---|---|
| `GuiIngame.renderGameOverlay` | Before every `RETURN`: calls `TimerRenderer`, `NotificationRenderer`, `TntHUD`, `FireballHUD`, and `BlockHitDetector.tick()` |
| `GuiNewChat.printChatMessage` | At method entry: calls `ChatInterceptor.onChatMessage()` with the chat component |
| `EntityPlayerSP.sendChatMessage` | At method entry: calls `CommandHandler.handleCommand()`; returns early if it returns `true` |
| `Minecraft.loadWorld` | At method entry: calls `MCCTimerState.onWorldChange()` to reset the timer on disconnect |
| `NetHandlerPlayClient.addToSendQueue` | At method entry: calls `ChatInterceptor.onPacketSend()`; cancels tab-complete packets for custom commands |
| `RenderGlobal.renderEntities` | Before every `RETURN`: calls `TrajectoryRenderer.renderTrajectories()` |
| `Explosion.doExplosionB` | At method entry: calls `TrajectoryRenderer.recordExplosion()` for blast radius auto-calibration |

On attach (mid-game injection), `premain` also retransforms all already-loaded classes to apply hooks retroactively.

All injected bytecode is wrapped in `try { ... } catch (Throwable) {}` so a missing method never crashes the game.

### Safe ClassWriter pattern

Every `ClassWriter` in this project overrides `getCommonSuperClass` to always return `"java/lang/Object"`. This prevents ASM from trying to load classes through Lunar's classloader during frame computation, which would throw errors.

### Key classes

- **`MCCTimerState`** — Single source of truth: static fields for timer running state, HUD positions/scales, edit mode flags, notification state, block hit settings. Persists to `~/mcctimer-config.properties` via Java `Properties`. Also detects current server IP and stores per-server blast radii.
- **`CommandHandler`** — Routes typed chat commands (`/gui`, `/blockhit`, etc.). Returns `true` to suppress sending to server. Register new commands by adding to the static `commands` map and implementing `Command`.
- **`ChatInterceptor`** — Parses incoming chat text (via `getFormattedText()`/Notch `d()`) to start/stop the timer on specific MCC game messages. Also intercepts tab-complete packets to provide completions for custom commands.
- **`ChatCopyTransformer`** — Fingerprints Lunar's obfuscated Chat Copy class by scanning for the string constants `"copyChat"` and `"copiedMessage"`, then replaces the `getTextContent(Component)` INVOKESTATIC with a reflection call to `ChatCopyHooks.getTextOrLegacy()`.
- **`EditScreenFactory`** — Generates a `GuiScreen` subclass at runtime with ASM, then defines it into Minecraft's classloader using `MethodHandles.privateLookupIn().defineClass()`. The generated screen overrides `drawScreen` to call `TimerRenderer.handleEditInput(mouseX, mouseY)` each frame.
- **`NotificationRenderer`** — First tries to discover and use Lunar's native notification manager (by scanning `com.moonsworth.lunar.client.*` for a class with `ConcurrentLinkedQueue` fields). Falls back to a custom OpenGL rounded-rect renderer. UUID resolution goes: tab list → Mojang API.
- **`TrajectoryRenderer`** — Traces fireball entity motion vectors, ray-traces to find impact blocks, and highlights all blocks in the blast radius sphere (excluding air and end stone).
- **`AgentLogger`** — Appends timestamped lines to `~/mcctimer-debug.log`. Every component logs init success/failure here.

### Adding a new command

1. Create a class implementing `Command` (in `com.mcctimer.agent`).
2. Add it to the `static {}` block in `CommandHandler`.
3. Tab completion: override `onTabComplete()`; the interceptor handles packet cancellation automatically.

### Adding a new HUD element

1. Create a renderer class with a `public static void renderXxx(float partialTicks)` method.
2. In `MCCTimerAgent.transformGuiIngame()`, add an `injectReflectiveCall(mv, "com.mcctimer.agent.YourRenderer", "renderXxx", true, 1)` before the existing `RETURN` injections.
3. Add position/scale state to `MCCTimerState` and wire save/load in `saveConfig()`/`loadConfig()`.
4. Hook drag/scroll into `TimerRenderer.handleMouse()` and `handleEditInput()`.

## Runtime artifacts

| File | Description |
|---|---|
| `~/mcctimer-config.properties` | Persisted HUD positions, scales, and per-server blast radii |
| `~/mcctimer-debug.log` | Append-only log from `AgentLogger` |
