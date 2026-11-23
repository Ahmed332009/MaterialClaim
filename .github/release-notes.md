**Border on right click, brighter particles, a shorter selection outline, and a first-join
welcome.** Everything from 0.4.1 keeps working.

## MaterialClaim_Block

- **Right click the air with the shovel inside your own land shows the claim border** for
  30 seconds, with the name of the claim and the remaining time in chat. Clicking the air
  also cancels a half-made selection, so nobody gets stuck mid-claim.
- **Selection outlines now draw the border only**, never a fill, so a 200x150 claim no longer
  spams particles over all 30,000 blocks. Cost depends on the perimeter, not the area.
- **Brighter particles.** `END_ROD` by default (bright white, no gravity, visible from far
  away) instead of the old pale villager particles, drawn at ground level and at eye height
  so the line is readable across uneven terrain.
- **Border time raised to 30 seconds**, and asking again restarts the timer instead of
  stacking a second overlapping outline. `Min` 8 seconds is gone.
- **Shovel hints.** The first time you hold a golden shovel you get one short line telling
  you what it does: inside your land it points at the border, outside it explains the two
  corners. There is always a hint when a click could not do anything.

All of it is configurable:

```yaml
borders:
  show-seconds: 30
  particle: END_ROD
  density: 2
```

## First join welcome

A player who has **never played on the server before** is greeted once and told the server
has a land claim plugin, how the shovel works, and that `/claim` opens the menu. Returning
players are never greeted, even if they own no land. Every line is a normal `messages:`
entry, so you can reword it, or set `welcome-first-join: false` to turn it off.

## MaterialClaim_Chunk

Unchanged.

Needs Paper 1.21.1+ and Java 21+.
