# The Watcher

**The Watcher** is an early-development Minecraft horror mod for **Fabric 1.21.1**.

The project is intended to build tension through a persistent stalking entity and escalating encounters. It is currently a development project, not a finished or feature-complete horror experience.

## Current status

The Fabric project setup and GeckoLib dependency are in place. Core gameplay, the Watcher entity, animations, and horror events are still under development.

Planned direction:
- A hostile entity that stalks the player over time
- Escalating encounters rather than only scripted jumpscares
- Custom entity animation using GeckoLib
- A focused, atmospheric horror experience

These are goals, not claims that the features are already implemented.

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
