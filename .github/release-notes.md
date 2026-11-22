**Restores 0.3.4 behaviour and actually takes the 24 hours off the clock.**

This release undoes everything 0.3.5 changed and fixes the real cause of the missing
deduction. Nothing about how ownership works has changed, so no existing territory can
lose anything it had before.

- **The 24 hours are now really deducted.** The cause was an admin exemption in the
  purchase check: an OP (or anyone with `materialclaim.admin`) skipped the deduction while
  the protection was still marked as bought. So the count went up and the upkeep cost rose,
  but the clock never moved. Everyone now pays the 24 hours, and a server-owned territory
  (spawn, shops) starts fully bought so it is never charged.
- **The upkeep clock is shown on the settings screen.** A purchase is now visibly 24 hours:
  open the menu, buy, reopen it, and the "Upkeep left" line is 24 hours lower.
- **Free toggles no longer open a buy screen.** Switching a free toggle (visitor doors,
  containers, animals, mob griefing, fluid flow, crop trample) ON used to open a
  "Buy ... for 24 hours?" confirmation, because free toggles are never "purchased". They
  are plain free toggles again.
- **Every 0.3.5 change is reverted.** In particular, switching a protection on still marks
  it bought exactly as before, so "Protections bought" cannot drop to 0, and an existing
  protection you already had on stays owned and free to toggle.

Unchanged from 0.3.4, and kept on purpose:

- A protection costs exactly 24 hours of upkeep. `3d 23h` becomes `2d 23h`; under 24 hours
  the purchase is refused with a clear message and nothing changes.
- Confirming a purchase raises upkeep cost by 25% permanently, stacking to 2x when all four
  are bought, so a diamond goes 12h, 9.6h, 8h, 6.9h, 6h. The rise stays even while a
  protection is switched OFF.
- Toggling a bought protection, and changing PvP mode after buying PvP, are free.
- Nothing is ever refunded.

- Chest menu works on Java and Bedrock (Geyser). Protection settings use normal taps only.
- Needs Paper 1.21.1+ and Java 21+.
