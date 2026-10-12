# The Watcher

**The Watcher** is an early-development Minecraft horror mod for **Fabric 1.21.1**.

The mod focuses on a custom stalking entity, unnerving audio cues, darker nights, and encounters that escalate instead of behaving like a standard zombie-style mob. It is still under active development; expect rough edges and report bugs with your latest log.

## Current gameplay

- The Watcher has its own phases: **observing**, **stalking**, **escalating**, and **retreating**.
- When the player directly watches it, The Watcher freezes completely. It has no look-around goal and always uses its idle animation.
- During stalking, The Watcher does not walk toward the player. It uses the live Minecraft render-distance option, and relocates to a narrow band just inside the visible distance edge. If it starts too close, it prioritizes moving away as soon as you stop looking at it. Candidate positions must be safe and outside your current view or hidden by solid cover. Stalking teleports are silent; the intent is to catch a distant silhouette at the edge of visibility, not to pop up beside you.
- Render distance is sampled continuously, so changing Minecraft's video setting takes effect without reopening The Watcher Settings. `/watcher debug status` reports the current stalking distance band.
- The Watcher Settings screen includes a render-distance control that changes Minecraft's actual view distance and uses it to calculate long-range stalking positions.
- Retreating is deliberately abrupt: while unseen, it can jump to a covered position; while visible, it sprints away instead of teleporting.
- Escalation makes it pursue the player and allows one attack attempt per escalation.
- Distant stalking can trigger rare false footsteps behind the player, quiet cave ambience, or a brief ash disturbance near the edge of vision. Cues are intentionally sparse and do not reveal the Watcher’s exact location.
- Close encounters can trigger randomized cave ambience, an Enderman-like stare cue, and short Darkness bursts instead of constant predictable effects.
- The pause menu includes **The Watcher Settings** for toggling dread effects, night darkness, and unpredictable teleporting.
- Overworld nights apply extra Darkness by default. Operators can disable this for testing.
- The entity does not use the usual automatic target and continuous melee goals.

Natural spawning is not yet considered release-ready; use `/watcher debug spawn` or vanilla `/summon the-watcher:watcher` for predictable testing. The entity is persistent while stalking so vanilla distance-based despawning cannot break the render-edge mechanic; natural encounters clean themselves up after 30 seconds of daytime.

## Debug commands

These commands require operator permission level 2 and are intended for a world/server where you have permission to use commands.

| Command | Effect |
| --- | --- |
| `/watcher debug` | Show the command list |
| `/watcher debug spawn` | Spawn a Watcher on safe ground in front of you |
| `/watcher debug status` | Show phase, phase ticks, debug lock, health, distance, and target |
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

During a run, the mod can construct up to five watchtowers in suitable, already-loaded, flat terrain. Each tower has a chest with randomized supplies and a logbook describing earlier inhabitants and their encounters with the Watcher. Tower placement is randomized and may be delayed when no suitable terrain is available.

This progression is an early implementation: the run is randomized, but the learning model currently uses exploration/movement as its main signal. World-generation and in-game behavior still need a full Gradle build and in-game verification.
