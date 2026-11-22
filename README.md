# MaterialClaim

**Two plugins ship in one release. Install ONE of them:**

| Jar | Plugin | Claiming |
| --- | --- | --- |
| `MaterialClaim_Chunk` | `MaterialClaim_Chunk` | One 16x16 chunk at a time (identical to 0.3.6) |
| `MaterialClaim_Block` | `MaterialClaim_Block` | Any block rectangle with a golden shovel, GriefPrevention style |

Both use `/claims` and both claim land, so do not run them together on one server.
Everything below describes **MaterialClaim_Block**; the chunk plugin behaves exactly as
0.3.6 did, with chunk claiming in place of the shovel.

## Features

- Claim any rectangle of blocks, measured to the block, covering the whole vertical column.
- Touching or overlapping land you own joins one **territory**: one payment, one upkeep clock, one trust list.
- Upkeep grows with territory size through the `upkeep-tiers` setting in `config.yml`.
- Expanding over land you already own is free; you only pay for new blocks.
- Claims expire: upkeep runs out, a grace period follows, then the territory is removed.
- No economy plugin and no Vault.
- Chest-style GUI that works for Java and Bedrock players (via Geyser).
- Cost is paid with copper, iron, gold, diamond or netherite — pick the item, or leave it on Automatic (cheapest first).
- Trust system: give other players build access per territory.
- Particle border visualizer for any shape made of rectangles.
- Admin tools: bypass mode, server claims, force unclaim, reload.

## Claiming with the golden shovel

1. **Left click** a block with a golden shovel: that is the first corner. A particle outline
   follows your cursor so you can see the rectangle.
2. **Right click** the opposite corner. The **price menu opens automatically**, showing the
   exact size in blocks, the territory size after claiming, the upkeep rate, and the
   adjusted price for those blocks — pick the item to pay with, then confirm.

Prices are set in `config.yml` under `claim-price` and are **configurable in two modes**:

| Mode | Behaviour |
| --- | --- |
| `flat` (default) | Price is proportional to area. One 16x16 chunk (256 blocks) = 96 hours, so 1 block = 0.375h. Nothing changed economically from the old chunk system. |
| `tiered` | The per-block price is multiplied by a factor from `claim-price.tiers` chosen by area, so big claims can be cheaper per block. |

The **stick** right-clicked on a block tells you who owns it, its size and its upkeep.
Sneak + right click with the shovel on your own land removes that rectangle.

Claiming a brand new area costs `claim-price.per-chunk-hours` per 256 blocks (default
**96 hours** of item value), whatever size the selection is. Item values below are hours of
upkeep for **one chunk** (256 blocks). Blocks are worth **9x** the matching ingot (copper block 13.5, iron block 27, gold block 54, diamond block 108, netherite block 864). Only plain items count — renamed or enchanted items are ignored.

## Building both jars

The repository holds one plugin with two claim-style variants:

```
src/shared/   code identical in both plugins (protection, payments, settings, trust)
src/chunk/    MaterialClaim_Chunk sources + resources
src/block/    MaterialClaim_Block sources + resources
```

`./gradlew build` produces both jars in `build/libs/`:
`MaterialClaim_Chunk-<version>.jar` and `MaterialClaim_Block-<version>.jar`.
The two variants both use the package `dev.keepclaims` because they share the
`src/shared` classes, which is safe only because a server runs one of them.

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
| `claim-price.mode` | `flat` | `flat` = proportional to area, `tiered` = use the tier table |
| `claim-price.per-chunk-hours` | 96 | Claim price per 256 blocks, in hours of item value |
| `claim-price.minimum-blocks` | 100 | Smallest selection a player may claim |
| `claim-price.maximum-blocks` | 0 | Largest selection (0 = no limit) |
| `claim-price.tiers` | see config | Per-block multipliers for `tiered` mode |
| `upkeep-tiers` | 1.0 | How the hourly upkeep drain scales with territory size, in blocks |
| `tools.claim-tool` | GOLDEN_SHOVEL | Item used to select corners |
| `tools.inspect-tool` | STICK | Item used to inspect land |
| `max-claim-blocks` | 0 | Hard cap on total blocks per player (0 = use `max-claims-default`) |
| `max-prepaid-days` | 14 | Most upkeep anyone can bank on one territory |
| `grace-days` | 7 | Days a territory keeps protecting after upkeep ends |
| `warn-days` | 3 | Warn players on join below this many days left |
| `connect-diagonals` | false | Also join chunks that only touch at a corner |
| `enabled-worlds` | `[]` (all) | Worlds where claiming is allowed |
| `public-blocks` | crafting table, anvil, … | Blocks anyone may use inside another player's claim |
| `max-claims-default` | 25 | Total chunks' worth of blocks per player (`materialclaim.limit.<n>` raises it) |
| `materials` | see above | Accepted items and their value in hours |

## Territory settings

Every territory has its own settings. Open **Settings** from the main menu, or use the commands below.

**Paid protections** (explosions, PvP, mob spawning, fire spread) are **bought once** for exactly **24 hours of the territory's own upkeep**. A territory needs at least 24 hours left before it can buy one; below that the purchase is refused and nothing changes. The menu asks you to confirm first, and the confirmation spells out the 24 hours, the permanent cost rise and that there are no refunds.

After buying, that protection is yours for good: switching it off and on again is **free**, takes no hours, gives none back, and never lowers the cost. Every purchase permanently raises upkeep cost by `paid-protection-upkeep-multiplier` (25% each, counted even while the protection is switched off), so owning all four makes upkeep cost twice as much and a diamond worth 12h drops to 9.6h, 8h, 6.9h and finally 6h. PvP is the same: buy it once, then every mode change (everyone, visitors, trusted, nobody) is free.

Nothing is ever refunded, not when you toggle a protection off and not when you unclaim. If a territory runs out of upkeep its protections switch off but stay bought, so paying upkeep brings them back free.

| Protection | What it stops |
|---|---|
| Explosion Damage | TNT, creeper and wither block damage |
| PvP | Player vs player combat. One control with four choices: everyone (no protection), visitors only, trusted only, nobody |
| Mob Spawning | Hostile mobs spawning inside the territory |
| Fire Spread | Fire spreading to or from territory blocks |

**Free toggles** cost nothing. All of them start **blocked** on a new territory. Owners and managers can change them.

| Toggle | Meaning |
|---|---|
| Visitor Doors | Visitors may use doors, buttons and levers |
| Visitor Containers | Visitors may open chests, barrels and similar |
| Visitor Animals | Visitors may harm, leash or ride animals and vehicles |
| Mob Griefing | Endermen, withers and similar may break blocks |
| Fluid Flow | Fluids from neighbouring chunks may flow in |
| Crop Trampling | Visitors may trample crops |

Also available: a custom **territory name**, and **entry / leave messages** (`{territory}` and `{player}` placeholders, plain text only). Explosions only damage blocks in territories that have not bought explosion protection, so a protected neighbour is never hurt. Server claims (spawn, shops) are fully protected from the start. Unpaid protections switch off as soon as a territory is overdue; they are remembered as bought and can be switched back on for free after paying upkeep.

### Trust levels

| Level | Can do |
|---|---|
| Visitor | Subject to the free-toggle rules only |
| Builder | Place and break blocks |
| Manager | Builder, plus: pay upkeep, buy paid protections (the manager pays the 24h of upkeep), change free toggles, name and messages |

Only the owner (or an admin) can add or remove trusted players, change their level, or unclaim. Admins can do everything on every territory and never pay. In the menu, tap a trusted player to pick Builder, Manager or Remove.

## Commands

| Command | Description |
|---|---|
| `/claims` | Open the main menu (also `/claim`, `/kc`) |
| `/claims claim` | Quick-claim the 16x16 chunk you stand in (the shovel is normal) |
| `/claims unclaim` | Give up the whole territory you are standing in |
| `/claims shovel` | Gives you a golden shovel |
| `/claims stick` | Gives you a stick |
| `/claims pay` | Open the upkeep menu |
| `/claims info` | Show claim info for the land you stand on |
| `/claims list` | See all your territories |
| `/claims borders` | Show the territory border with particles |
| `/claims trust <player>` | Trust a player in this territory |
| `/claims untrust <player>` | Remove trust |
| `/claims flag <flag> <value> [territory]` | Change one setting |
| `/claims flags [territory]` | List every setting for a territory |

`<value>` is `on`/`off` for toggles, `everyone`/`visitors`/`trusted`/`nobody` for
`pvp-scope`, and free text for `name`, `entry-message` and `leave-message`. The optional
`[territory]` accepts a territory name, an owner name, or an `x,z` block position; without
it the land you are standing on is used.

## Admin

| Command | Description |
|---|---|
| `/claims admin bypass` | Toggle building anywhere |
| `/claims admin serverclaim [radius]` | Protect land for the server, radius in chunks (spawn, shops) |
| `/claims admin unclaim` | Remove the territory you are standing in |
| `/claims admin reload` | Reload `config.yml` |

Permissions:

| Permission | Default | Meaning |
|---|---|---|
| `materialclaim.use` | everyone | Use `/claims` and the menu |
| `materialclaim.limit.<n>` | — | Raises a player's total limit, in chunks' worth of blocks |
| `materialclaim.flags` | everyone | Change free toggles on your own territory |
| `materialclaim.flags.paid` | everyone | Change paid protections on your own territory |
| `materialclaim.flags.any` | op | Change any setting on any territory |
| `materialclaim.admin` | op | Full admin override |

## Building from source

The repository includes the Gradle wrapper, so no local Gradle install is needed.

```bash
./gradlew jar          # gradlew.bat jar on Windows
```

The jar lands in `build/libs/MaterialClaim-<version>.jar`. You need JDK 21 installed — Gradle will not download one for you.

Pushing a `v*` tag makes GitHub Actions build the jar and publish it as a Release automatically.

## License

GPL-3.0. See [LICENSE](LICENSE).
