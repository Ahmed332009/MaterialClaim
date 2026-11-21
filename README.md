# MaterialClaim

A land claim plugin for Paper 1.21+ servers. No economy plugin, no Vault, no diamonds-only system. Players claim chunks and pay upkeep with Minecraft materials. Claims run out if not maintained and are eventually removed.

## Features

- Chest-style GUI menu, works for Bedrock players through Geyser
- Multiple payment materials: copper, iron, gold, diamond, netherite (each worth different upkeep time)
- Claims expire: upkeep runs out, grace period, then the claim is deleted
- Cost tiers: first few claims are cheaper, many claims get more expensive
- No economy plugin required
- Admin tools: bypass mode, server claims, force unclaim, reload
- Trust system: grant other players build access per claim
- Chunk border visualizer

## Requirements

- Paper 1.21.1 or newer
- Java 21 or newer
- Geyser + Floodgate (optional, for Bedrock support)

## Installation

1. Download the latest `KeepClaims.jar` from [Releases](../../releases)
2. Place it in your server's `plugins/` folder
3. Restart the server
4. Edit `plugins/KeepClaims/config.yml` to your liking
5. Run `/claims admin reload` to apply config changes without a restart

## Commands

| Command | Description |
|---|---|
| `/claims` | Open the main menu |
| `/claims info` | Show claim info for the chunk you stand in |
| `/claims borders` | Show chunk border particles |
| `/claims trust <player>` | Trust a player in this claim |
| `/claims untrust <player>` | Remove trust |
| `/claims list` | See all your claims |
| `/claims admin bypass` | Toggle bypass mode (admin) |
| `/claims admin serverclaim [radius]` | Make permanent server claims (admin) |
| `/claims admin unclaim` | Force remove a claim (admin) |
| `/claims admin reload` | Reload config (admin) |

## Permissions

| Permission | Default | Description |
|---|---|---|
| `keepclaims.use` | Everyone | Use claims |
| `keepclaims.admin` | OP | Admin commands |
| `keepclaims.limit.<n>` | — | Set a player's max claims to n |

## Payment Materials (default)

| Material | Upkeep per chunk |
|---|---|
| Copper Ingot | 4 hours |
| Iron Ingot | 6 hours |
| Gold Ingot | 8 hours |
| Diamond | 72 hours (3 days) |
| Netherite Ingot | 240 hours (10 days) |

Blocks count as 9x the ingot value. All values are configurable.

## Configuration

All settings are in `plugins/KeepClaims/config.yml`. Key options:

- `initial-hours` — upkeep cost to claim a chunk (default 168 = 1 week)
- `max-prepaid-days` — maximum time a player can bank per claim (default 30)
- `grace-days` — how long after expiry before the claim is deleted (default 7)
- `cost-tiers` — multipliers based on how many claims a player has
- `enabled-worlds` — restrict claiming to specific worlds
- `public-blocks` — blocks anyone can use inside claims

## License

GPL-3.0 — see [LICENSE](LICENSE)
