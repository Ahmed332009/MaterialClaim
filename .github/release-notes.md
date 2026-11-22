**Bug fix: paid protections now really charge, and only once.**

In 0.3.0 the settings menu showed a fee for each paid protection, but the fee was not
applied the way it should have been. This release fixes it.

- **The fee is now actually taken.** Clicking a paid protection removes the items from
  your inventory straight away. If you cannot afford it, the protection stays off and
  nothing is taken.
- **The fee is charged only once per protection, per territory.** After the first
  purchase that protection is yours: turn it off and on as often as you like, at no
  extra cost. The menu shows "already bought - free to switch on" instead of a price.
- **The fee is 24 hours of item value** (was 100), which is 2 diamonds, 8 gold ingots or
  16 iron ingots. Change `paid-protection-activation-fee` in `config.yml` to alter it;
  `0` makes protections free.
- **The extra upkeep is now felt immediately.** Activating a protection re-prices the
  upkeep you have already banked at the new rate, so a territory with 100 hours banked
  drops to 80 hours when a protection adds 25%. Previously the increase only appeared on
  your next payment. Dropping protections when upkeep runs out now refunds the
  difference in the same way.

Upgrading from 0.3.0 needs nothing but the new jar. Territories keep their settings,
trust lists and banked upkeep; the first activation after upgrading charges the new
one-time 24 hour fee, then never again.

- Chest menu works on Java and Bedrock (Geyser).
- Needs Paper 1.21.1+ and Java 21+. These settings are new and have not been tested on a
  live server yet: please open an issue if something misbehaves.
