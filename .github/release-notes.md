**Two plugins in one release. Install ONE of them, not both.**

| Jar | Plugin name | Claiming |
| --- | --- | --- |
| `MaterialClaim_Chunk-0.4.0.jar` | `MaterialClaim_Chunk` | Claim one 16x16 chunk at a time. Identical to 0.3.6. |
| `MaterialClaim_Block-0.4.0.jar` | `MaterialClaim_Block` | Claim any block rectangle with a golden shovel, GriefPrevention style. |

Both plugins are complete and independent: same menu, same upkeep, same trust list, same
paid protections. They both use the `/claims` command and both claim land, so on one server
pick the one you want and leave the other out.

## MaterialClaim_Block: claim land in blocks

- **Golden shovel.** Left click a block for the first corner, right click the opposite
  corner for the second. A particle outline follows your cursor while you select.
- **The price menu opens automatically** on the second corner. It shows the exact size in
  blocks, the territory size after claiming, the upkeep rate, and the adjusted price for
  those blocks. Nothing is charged until you confirm.
- **Stick.** Right click any block to see who owns it, its size and its upkeep left.
- **Full vertical column.** A claim covers bedrock to build limit at those coordinates.
- **Merging.** Touching or overlapping land you already own joins one territory. Expanding
  over your own land only charges for the new blocks.
- **Shrinking.** Sneak + left click, then right click the opposite corner, to remove land.
  Free, and nothing is refunded.
- **Configurable pricing** in `config.yml`:
  - `claim-price.mode: flat` (default) or `tiered`
  - `claim-price.per-chunk-hours: 96` for 256 blocks, so the old economy is unchanged
  - `claim-price.tiers` for per-block discounts by area when `mode: tiered`
  - `upkeep-tiers` for how the hourly drain scales with size
  - `minimum-blocks`, `maximum-blocks`, `max-claim-blocks`
- Existing chunk claims in `claims.yml` are converted to 16x16 rectangles on first load,
  keeping owner, trust, settings, purchases and the upkeep clock.

## Shared with 0.3.6 (both jars)

- A paid protection costs exactly **24 hours** of territory upkeep, deducted from the clock.
  Under 24 hours left it is refused with a clear message. As of 0.3.6 this applies to
  everyone, including OPs, which is what made the deduction actually visible.
- **Confirm before buying** states the 24 hour cost, the permanent upkeep rise and that
  nothing is refunded.
- Buying a protection raises upkeep cost by 25% **permanently** and stacking, so a diamond
  worth 12h becomes 9.6h, 8h, 6.9h and finally 6h. The rise stays while a protection is
  switched off.
- Toggling a bought protection, and changing PvP mode after buying PvP, are free.
- **No refunds, ever.**

Needs Paper 1.21.1+ and Java 21+. Chest menu works on Java and Bedrock (Geyser).
