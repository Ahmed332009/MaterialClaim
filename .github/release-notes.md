**Fix: buying a protection now really takes its 24 hours.**

Buying a protection could silently cost nothing, so upkeep stayed at the same number even
after buying several. The cause was in how a protection decided whether it had already been
paid for.

- **The bug.** A protection was marked "bought" whenever it was switched on, not only when
  it was actually paid for. So an admin switching one on, or a protection left on by older
  saved data, quietly counted as already owned. Every later click was then treated as a free
  toggle and the 24 hours were never taken.
- **The fix.** Being "bought" is now only ever set by a purchase that really charged. Turning
  something on no longer implies you own it.
- **Buying works even if it was already on.** An unowned protection that happens to be
  switched on is now charged properly when the owner clicks it, instead of being skipped as
  "already on".
- **Still visible and safe.** The settings screen now shows the territory's remaining upkeep,
  and every purchase writes one line to the server console, for example
  `Protection bought: explosions by Steve on Home - upkeep 3d 23h -> 2d 23h`, so the
  deduction can be checked directly.
- **Enforcement follows the switch.** A protection shows as ON only if it really is enforced,
  including one an admin switched on. Ownership decides whether you still owe the purchase;
  it never makes an ON protection silently stop working.

Buying still costs exactly 24 hours off the territory clock, is refused below 24 hours, needs
a confirmation, raises upkeep cost permanently, and toggling a bought protection is still
free with no refunds.

- Chest menu works on Java and Bedrock (Geyser). Protection settings use normal taps only.
- Needs Paper 1.21.1+ and Java 21+. These settings have not been tested on a live server yet:
  please open an issue if something misbehaves.
