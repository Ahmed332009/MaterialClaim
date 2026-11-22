# MaterialClaim

Land claims paid with items instead of money. No economy plugin, no Vault. Players claim chunks and pay upkeep with Minecraft materials. Claims run out if not maintained and are eventually removed.

The chest menu also works for Bedrock players through Geyser.

**Honest status:** the GUI was tested on both Java and Bedrock in the 0.1.x versions. **Territories (version 0.2.0) are new and have not been tested on a live server yet** — if something misbehaves, please open an issue.

## Features

- Connected chunks form one **territory**: one payment, one upkeep clock, one trust list.
- Upkeep grows with territory size through the `group-upkeep` setting in `config.yml`.
- You can only unclaim a chunk if the rest of your territory stays connected.
- Claims expire: upkeep runs out, a grace period follows, then the territory is removed.
- No economy plugin and no Vault.
- Chest-style GUI that works for Java and Bedrock players (via Geyser).
- Cost is paid with copper, iron, gold, diamond or netherite — pick the item, or leave it on Automatic (cheapest first).
- Trust system: give other players build access per territory.
- Chunk border visualizer.
- Admin tools: bypass mode, server claims, force unclaim, reload.

## Claim price and payment materials

Claiming one chunk costs `initial-hours` = **96 hours** of item value. That is:

- 64 copper ingots
- 32 iron ingots
- 16 gold ingots
- 8 diamonds
- 1 netherite ingot

| Material | Hours of upkeep |
|---|---|
| Copper ingot | 1.5 |
| Iron ingot | 3 |
| Gold ingot | 6 |
| Diamond | 12 |
| Netherite ingot | 96 |

All values are hours of upkeep for **one chunk**. Blocks are worth **9x** the matching ingot (copper block 13.5, iron block 27, gold block 54, diamond block 108, netherite block 864). Only plain items count — renamed or enchanted items are ignored.

## Requirements

- Paper 1.21.1 or newer (built against the 1.21.11 API)
- Java 21 or newer
- Geyser + Floodgate (optional, for Bedrock players)

## Installation

1. Download `MaterialClaim-<version>.jar` from [Releases](../../releases).
2. Put it in your server's `plugins/` folder.
3. Restart the server.
4. Edit `plugins/MaterialClaim/config.yml` to your liking.
5. Run `/claims admin reload` to apply config changes without a restart.

**Upgrading from an older version:** delete the old `plugins/MaterialClaim/config.yml` once so the new defaults are created. Old `claims.yml` files are converted automatically on load, and a backup named `claims-old-format.yml` is saved first.

Do not run this together with DiamondClaims for long: both use the `/claim` command and both protect chunks. Old DiamondClaims claims are not imported yet.

## Configuration

| Key | Default | Meaning |
|---|---|---|
| `initial-hours` | 96 | Claim price per chunk, in hours of item value |
| `max-prepaid-days` | 14 | Most upkeep anyone can bank on one territory |
| `grace-days` | 7 | Days a territory keeps protecting after upkeep ends |
| `warn-days` | 3 | Warn players on join below this many days left |
| `group-upkeep` | 1.0 up to 1000 chunks | How upkeep scales with territory size (replaces the old `cost-tiers`) |
| `connect-diagonals` | false | Also join chunks that only touch at a corner |
| `enabled-worlds` | `[]` (all) | Worlds where claiming is allowed |
| `public-blocks` | crafting table, anvil, … | Blocks anyone may use inside another player's claim |
| `max-claims-default` | 25 | Total chunks per player (`materialclaim.limit.<n>` raises it) |
| `materials` | see above | Accepted items and their value in hours |

## Commands

| Command | Description |
|---|---|
| `/claims` | Open the main menu (also `/claim`, `/kc`) |
| `/claims claim` | Claim the chunk you are standing in |
| `/claims unclaim` | Unclaim the chunk you are standing in |
| `/claims pay` | Open the upkeep menu |
| `/claims info` | Show claim info for the chunk you stand in |
| `/claims list` | See all your territories |
| `/claims borders` | Show the territory border with particles |
| `/claims trust <player>` | Trust a player in this territory |
| `/claims untrust <player>` | Remove trust |

## Admin

| Command | Description |
|---|---|
| `/claims admin bypass` | Toggle building anywhere |
| `/claims admin serverclaim [radius]` | Protect chunks for the server (spawn, shops) |
| `/claims admin unclaim` | Remove the territory you are standing in |
| `/claims admin reload` | Reload `config.yml` |

Permissions: `materialclaim.use`, `materialclaim.admin`, and `materialclaim.limit.<n>`.

## Building from source

The repository includes the Gradle wrapper, so no local Gradle install is needed.

```bash
./gradlew jar          # gradlew.bat jar on Windows
```

The jar lands in `build/libs/MaterialClaim-<version>.jar`. You need JDK 21 installed — Gradle will not download one for you.

Pushing a `v*` tag makes GitHub Actions build the jar and publish it as a Release automatically.

## License

GPL-3.0. See [LICENSE](LICENSE).
