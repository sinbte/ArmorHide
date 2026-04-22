# ArmorHide

A lightweight armor-hiding / cosmetic-hat plugin for **Paper, Purpur and [Folia](https://github.com/PaperMC/Folia)**. Players can hide their worn armor from everyone, or wear any item as a cosmetic hat — without losing the real armor's stats.

It uses only standard Bukkit API plus ProtocolLib; the Folia region/entity schedulers it calls are also implemented on Paper/Purpur, so the same jar runs on all three.

## Features

- **`/hidearmor`** — toggle your armor's visibility. While hidden:
  - Other players (and your own F5 view) no longer see your armor; your head shows your player skull instead.
  - In your inventory the four armor slots show a lime glass pane whose hover text keeps the original item's name, enchantments and durability.
  - Unequipping reveals the real item; putting it back shows the placeholder again.
  - The elytra stays visible (so gliding still looks right).
- **`/cosmetichat [off]`** — wear the item in your main hand as a cosmetic hat visible to others, while keeping your real helmet's stats. `off` removes it.
- Settings persist across reconnects and restarts in a local `playerdata.yml` — no database required.

Command aliases: `/togglearmor`, `/hat`.

## Requirements

| | |
|---|---|
| Server | Folia 26.1.2 (build 8) or newer |
| Java | 25 or newer |
| **ProtocolLib** | **dev-build (5.5.0-SNAPSHOT) or newer** — see note below |

> **ProtocolLib version matters.** The whole feature works by rewriting equipment/inventory packets, so it needs ProtocolLib. The stable **5.4.0 release only supports up to Minecraft 1.21.8** and will fail to load on 26.1.2. You must use the ProtocolLib **[dev-build](https://github.com/dmulloy2/ProtocolLib/releases/tag/dev-build)** (5.5.0-SNAPSHOT, "beta support for Minecraft 26.1"), which is `folia-supported`. ArmorHide disables itself cleanly if ProtocolLib is missing or fails to enable.

## Server software

| Software | Supported |
|---|---|
| Folia | ✅ Primary target — `folia-supported: true` |
| Paper / Purpur | ✅ Standard Bukkit API + ProtocolLib; same jar works |

(Provided a ProtocolLib build that supports your Minecraft version is installed.)

## Building

```bash
mvn clean package
```

The jar is produced at `target/ArmorHide-1.0.0.jar`. Drop it into your server's `plugins/` folder alongside ProtocolLib.

## Configuration

```yaml
# config.yml
language: en
```

On first start the plugin writes `config.yml`, `lang/en.yml` and (on first use) `playerdata.yml` into its data folder. Existing files are never overwritten. Missing keys in a custom language file fall back to the bundled English defaults.

## License

MIT
