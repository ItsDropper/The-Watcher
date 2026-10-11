# The Watcher

**The Watcher** is an early-development Minecraft horror mod for **Fabric 1.21.1**.

The mod focuses on a custom stalking entity, unnerving audio cues, darker nights, and encounters that escalate instead of behaving like a standard zombie-style mob. It is still under active development; expect rough edges and report bugs with your latest log.

## Current gameplay

- The Watcher has its own phases: **observing**, **stalking**, **escalating**, and **retreating**.
- During stalking, it approaches when you are not looking directly at it and freezes when you notice it.
- Escalation makes it pursue the player and allows one attack attempt per escalation.
- When it is nearby, occasional Enderman-like audio cues and short Darkness bursts add pressure.
- Overworld nights apply extra Darkness by default. Operators can disable this for testing.
- The entity does not use the usual automatic target and continuous melee goals.

The Watcher currently does **not** have natural spawn rules. Spawn it for testing with the debug command or vanilla `/summon the-watcher:watcher`.

## Debug commands

These commands require operator permission level 2 and are intended for a world/server where you have permission to use commands.

| Command | Effect |
| --- | --- |
| `/watcher debug` | Show the command list |
| `/watcher debug spawn` | Spawn a Watcher five blocks in front of you |
| `/watcher debug status` | Show phase, phase ticks, debug lock, health, distance, and target |
| `/watcher debug phase observe` | Force it to observe and look at you |
| `/watcher debug phase stalk` | Lock it into stalking; it advances when you are not looking at it |
| `/watcher debug phase escalate` | Lock it into escalation; it pursues and attacks once when close |
| `/watcher debug phase retreat` | Lock it into retreating behavior |
| `/watcher debug phase auto` | Remove the debug lock and resume automatic night behavior |
| `/watcher debug darkness on` | Enable extra Overworld night darkness |
| `/watcher debug darkness off` | Disable extra Overworld night darkness |
| `/watcher debug despawn` | Remove the nearest Watcher within 64 blocks |

A phase forced with a debug command stays locked until `/watcher debug phase auto` is used. This lets you test the behavior during daytime and without waiting for the normal phase timer.

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
