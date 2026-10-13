# The Watcher

**The Watcher** is an early-development Minecraft horror mod for **Fabric 1.21.1**.

The mod focuses on a custom stalking entity, unnerving audio cues, darker nights, and encounters that escalate instead of behaving like a standard zombie-style mob. It is still under active development; expect rough edges and report bugs with your latest log.

## Current gameplay

- The Watcher has its own phases: **observing**, **stalking**, **escalating**, and **retreating**.
- When the player directly watches it, The Watcher freezes completely. It has no look-around goal and always uses its idle animation.
- During observing, The Watcher wanders around nearby surveillance cameras instead of standing still. It walks away if it gets too close; it can teleport only during stalking, and only while outside your view or hidden by solid cover. The first reveal now starts farther away, scaled to render distance. Stalking teleports are silent; the goal is a silhouette you can actually spot, not an entity permanently at the render edge.
- Render distance is sampled continuously, so changing Minecraft's video setting takes effect without reopening The Watcher Settings. `/watcher debug status` reports the current stalking distance band.
- The Watcher Settings screen includes a render-distance control that changes Minecraft's actual view distance and uses it to calculate long-range stalking positions.
- Retreating is physical movement: it runs away instead of teleporting. Teleport relocation is exclusive to stalking.
- Escalation makes it pursue the player and allows one attack attempt per escalation.
- Distant stalking can trigger rare false footsteps behind the player, quiet cave ambience, or a brief ash disturbance near the edge of vision. Cues are intentionally sparse and do not reveal the Watcher’s exact location.
- Close encounters can trigger randomized cave ambience, an Enderman-like stare cue, and short Darkness bursts instead of constant predictable effects.
- The pause menu includes **The Watcher Settings** for toggling dread effects, night darkness, and unpredictable teleporting.
- Overworld nights apply extra Darkness by default. Operators can disable this for testing.
- The entity does not use the usual automatic target and continuous melee goals.

### Optional native desktop horror popups (Windows)
To trigger one immediately for testing, join a world and run the client-side command `/watcherclient debug desktop-popup`. This manual debug command works even when random desktop popups are disabled; random events still require the opt-in setting.


In **The Watcher Settings → Gameplay**, enable **Native desktop popups** to opt in. When enabled, a rare event can open a real Windows desktop message box over the game, using a separate PowerShell process rather than Minecraft's Java process. It is disabled by default, only runs in the Overworld, and uses fictional messages titled **The Watcher**—it does not impersonate Windows Security or claim that a real system problem occurred. Turn the option off to stop future popups. This feature is Windows-only and requires Windows PowerShell.

Natural runs begin with a hidden-learning phase and then announce the first reveal with `Watcher joined the game`. After that reveal, the run manager checks the natural entity every second and retries a replacement after ten seconds of absence. Use `/watcher debug spawn` or vanilla `/summon the-watcher:watcher` for predictable testing.

## Surveillance cameras (experimental)

Place the **Surveillance Camera** block from the Redstone creative tab, or use `/give @s the-watcher:surveillance_camera` in a world with commands enabled.

- Cameras check all directions within 48 blocks; a clear line of sight to The Watcher triggers a top-right camera-network alert without revealing the entity's coordinates.
- A small chance of signal interference replaces the motion warning with **SIGNAL LOST**.
- Cameras can be crafted with 4 iron ingots, 2 redstone dust, and 1 spyglass. They can also appear as loot in the chests of the custom watchtower, abandoned cabin, ritual shrine, and ruined camp.
- Placed cameras persist with their saved chunks and The Watcher no longer destroys them. Players can still break and pick them up normally.
- Press **V** (rebindable in Options → Controls) to open the **Camera Network** menu. The sharp, non-pausing overlay lists cameras found in currently loaded chunks within roughly 96 blocks, with coordinates and detection status. After teleporting back, let the area load and press **Refresh** if needed.
- Select **VIEW** beside a camera to open its live rendered viewpoint. Use **W/S** to tilt and **A/D** to pan; **Esc** returns to the network. The feed uses already-loaded world chunks, so cameras outside loaded areas are not remotely streamed. Recording history is not implemented.

## Debug commands

These commands require operator permission level 2 and are intended for a world/server where you have permission to use commands.

| Command | Effect |
| --- | --- |
| `/watcher debug` | Show the command list |
| `/watcher debug spawn` | Spawn a Watcher on safe ground in front of you |
| `/watcher debug status` | Show phase, distance, stalking band, and learned movement habits |
| `/watcher debug structures` | Print the guaranteed watchtower, cabin, shrine, and camp coordinates |
| `/watcher debug phase observe` | Lock it into the stationary observing phase |
| `/watcher debug phase stalk` | Lock it into stationary stalking with hidden long-range teleports |
| `/watcher debug phase escalate` | Trigger a short pursuit/attack rush, then retreat automatically |
| `/watcher debug phase retreat` | Lock it into retreating behavior |
| `/watcher debug phase auto` | Remove the debug lock and resume automatic night behavior |
| `/watcher debug darkness on` | Enable extra Overworld night darkness |
| `/watcher debug darkness off` | Disable extra Overworld night darkness |
| `/watcher debug despawn` | Remove the nearest Watcher within 64 blocks |
| `/watcher debug simulate` | Place a Watcher nearby in clear sight and run an accelerated observe → stalk → escalate → retreat encounter against you |
| `/watcher debug simulate stop` | Stop the simulated encounter |

Debug commands search a wide area so the Watcher can still be recovered after a distant relocation. Forced observe, stalk, and retreat phases stay locked until `/watcher debug phase auto` is used. Forced escalation performs a short rush and retreat, then locks again. The simulation command runs an accelerated encounter during any time of day; stop it with `/watcher debug simulate stop`. These commands let you test without waiting for normal phase timers.

## Horror expansion: structures and local AI

The run can now place several distinct exploration sites in suitable, already-loaded terrain in addition to the guaranteed watchtower:

- **Abandoned cabin** — a boarded-up shelter with a survivor's journal.
- **Ritual shrine** — a moss-covered stone site with chained pillars and a multi-part account of the failed offerings.
- **Ruined expedition camp** — a derelict campsite with an expedition log.
- **Watchtower** — the original lookout with its own logbook and supplies.

Each site uses a different book to reveal another part of the setting. These are currently built by the run manager in existing loaded terrain, not registered as vanilla world-generation structures. If no valid site can be placed, the run retries instead of treating the attempt as successful.

### Optional Ollama horror director

The Watcher can optionally connect to a **local Ollama** instance. This is disabled by default and uses the configured endpoint (default `http://localhost:11434`) and model name (default `llama3.2`). In the pause menu, open **The Watcher Settings**, enable **Local Ollama AI**, enter the model name installed in Ollama, select a system-prompt preset or edit the prompt, and save.

The model is asked for a small JSON directive at a slow interval. It can choose a short in-game whisper, a brief Darkness effect, false footsteps, or a bounded Watcher phase (observe, stalk, retreat). It can also choose silence. The model cannot run commands, execute code, or directly change arbitrary blocks. If Ollama is unavailable, the ordinary non-AI horror behavior continues; network failures are logged without blocking the server tick.

To use it, install and run Ollama locally, pull a model you have available, then enter that exact model name in the settings. The model name is not downloaded automatically by the mod. For a LAN world, the Ollama endpoint must be reachable from the machine running the integrated/server world. Only send prompts to an endpoint you trust.

## Requirements

- Minecraft **1.21.1**
- Fabric Loader **0.19.5 or newer**
- Java **21 or newer**
- GeckoLib **4.8.3 or compatible version** for Minecraft 1.21.1

A compatible Fabric installation and GeckoLib are required to run the mod.

## Development setup

1. Install a JDK 21 distribution.
2. Clone this repository.
3. Open the project in IntelliJ IDEA or another IDE with Gradle support.
4. Allow Gradle to sync dependencies.
5. Run the development client with:

   ```powershell
   .\gradlew runClient
   ```

To build the mod JAR:

```powershell
.\gradlew build
```

The built artifact is normally written to `build/libs/`.

For general Fabric development guidance, see the [Fabric documentation](https://docs.fabricmc.net/develop/).

## Project

- **Source:** [ItsDropper/The-Watcher](https://github.com/ItsDropper/The-Watcher)
- **Issues and bug reports:** [GitHub Issues](https://github.com/ItsDropper/The-Watcher/issues)

## License

This project is licensed under the [MIT License](LICENSE).

## Watcher difficulty and run progression

The world-creation screen includes **Difficulty: Watcher** and **Run length** controls. Enabling Watcher difficulty selects the Large Biomes world preset. With the mode off, the mod does not start a natural Watcher run; the `/watcher debug` commands remain available for testing.

A run begins with a randomized hidden-learning period. No natural Watcher entity exists during this stage, while the run samples player movement and exploration. The first reveal is delayed until a later night and uses the chat message `Watcher joined the game`. After the reveal, the Watcher can begin its normal stalking cycle. Run lengths are 1, 3, 6, or 12 hours of active play, counted by server ticks; the run state is saved with the world.

During a run, the mod guarantees a watchtower, abandoned cabin, ritual shrine, and ruined expedition camp near world spawn when safe placement is available. Each site has a distinct book and supplies. Additional structures can appear as players explore; guaranteed placements retry when terrain or region conflicts prevent placement.

This progression is still under active development. The adaptive model now tracks gaze timing, movement speed, sprinting, stationary behavior, typical height, and repeated close encounters. World-generation integration and in-game behavior still need gameplay verification.


## Experimental world hosting

The multiplayer screen and pause menu now include **Watcher Host / Host / Join World**. The host opens the single-player world to LAN and creates an invite; guests paste that invite and the mod connects them through a local loopback proxy.

- The optional relay service lives in [`hosting/relay`](hosting/relay) and uses in-memory room state only; it has no database.
- Relay transport requires TLS/WSS. The invite's 256-bit encryption secret is kept client-side and is not sent to the relay. Tunnel data uses AES-GCM with directional, per-channel keys and authenticated sequence numbers.
- The relay has bounded message sizes, connection/room limits, rate limits, slow-peer handling, and automated protocol tests.
- **A public relay has not been deployed by this repository.** To use hosting over the internet, configure the Hosting screen with a trusted WSS relay you operate. See [hosting deployment and security notes](hosting/README.md).
- This is an early implementation, not yet security-audited or verified in a real two-player session. Do not treat a successful build as proof that internet hosting works on every network. The normal dedicated-server path remains independent of the relay.
