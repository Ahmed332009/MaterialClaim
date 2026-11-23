**Adds a full staff command set for managing land you do not own** - delete it, transfer it,
wipe a player's land, override claim limits, and set upkeep clocks. Modelled on the admin
commands in GriefPrevention and Lands so the same jobs are findable.

## `/claims admin help`

Tab-complete lists all of these, and running `/claims admin` with no arguments prints the
list. Everything needs `materialclaim.admin` (ops have it).

## Managing land

| Command | What it does |
| --- | --- |
| `/claims admin delete` | Removes the **whole** territory you stand in, every rectangle of it, whoever owns it |
| `/claims admin deleteplayer <player>` | Removes **all** land owned by a player |
| `/claims admin deleteserver` | Removes every server claim |
| `/claims admin transfer <player>` | Gives the territory you stand in to another player |
| `/claims admin serverclaim [radius]` | Protects a square around you (radius 0-10 chunks) |
| `/claims admin unclaim` | Removes the whole territory you stand in (same as delete) |

From GriefPrevention these are `/deleteclaim`, `/deleteallclaims`, `/deletealladminclaims`
and `/transferclaim`.

- **Transfer** keeps the territory's settings and its upkeep clock, but **clears the trust
  list**, so the previous owner's friends lose access. That is stated in the confirmation.
- **`deleteserver` refuses to delete more than 500 server claims in one command**, so a
  mistyped command cannot wipe a whole spawn network. Delete in batches.
- Nothing is ever refunded by any of these.

## Limits and time

| Command | What it does |
| --- | --- |
| `/claims admin setlimit <player> <blocks>` | Sets that player's total land limit. **0 clears it** back to the default |
| `/claims admin setupkeep <player> <hours>` | Sets the upkeep clock on all their territories, clamped to the max-prepaid cap |
| `/claims admin setupkeep <player> status` | Shows the clock and status of each of their territories |

`setlimit` is the equivalent of GriefPrevention's `/adjustbonusclaimblocks`. Overrides are
stored in `limits.yml` and survive restarts.

## Look things up

| Command | What it does |
| --- | --- |
| `/claims admin list <player>` | Every territory a player owns, with sizes and upkeep |
| `/claims admin who` | Every claim in the world you are standing in |
| `/claims admin info [player]` | Detailed readout: owner and UUID, rectangles, world, upkeep rate, protections, trusted count |

## MaterialClaim_Chunk

Unchanged.

Needs Paper 1.21.1+ and Java 21+.
