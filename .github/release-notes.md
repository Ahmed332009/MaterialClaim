**Protections: correct on/off messages, buy once then free, and stale lore fixed.**

This release fixes the claim protection menu (explosions, mob spawning, fire spread and
PvP). Nothing about claiming, upkeep or trust has changed.

- **On and off now say the right thing.** Turning a protection on printed "now off" in
  chat, and the menu could show the opposite of the real state. A paid protection used to
  store the inverted value (true meant "vanilla"), and that raw value was being printed.
  Each protection now keeps the two things it needs separately: whether it has been
  **bought**, and whether it is **switched on**. Chat and the menu read the same value, so
  they cannot disagree with each other or with what the plugin actually blocks.
- **Buy once, then toggle free forever.** The first time you switch a protection on you pay
  the one-time fee and it becomes yours. After that, switching it off and on again costs
  nothing, as often as you like. If you cannot afford the fee, nothing is charged and the
  protection stays off and unbought.
- **Nothing is ever refunded.** Switching a protection off keeps it bought. Unclaiming gives
  no items back. If a territory runs out of upkeep its protections switch off but stay
  bought, so paying upkeep brings them back for free.
- **No more stale menu text.** The menu used to keep showing the "buy" price after you had
  already bought a protection. The lore is now rebuilt from the bought/on state every time
  it is drawn, so it reads Not purchased, ON (protecting), or OFF (already purchased)
  correctly, and the "protections ON" count matches reality.
- **The fee is exactly 24 hours of item value.** It used to be scaled by territory size and
  could pick up the +25% per-protection upkeep multiplier, so the amount taken was not
  clean. It is now charged once at a flat rate: 16 copper, 8 iron, 4 gold or 2 diamonds.

Your existing territories are upgraded automatically on first load: a protection that was
switched on and protecting before is kept on and marked as already bought, so you are not
charged a second time for something you were already paying for. Anything that was off
shows as not purchased and can be bought normally. No config edit is needed.

- Chest menu works on Java and Bedrock (Geyser). Protection settings use normal taps only.
- Needs Paper 1.21.1+ and Java 21+. These settings have not been tested on a live server yet:
  please open an issue if something misbehaves.
