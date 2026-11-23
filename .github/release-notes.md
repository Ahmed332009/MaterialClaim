**The claim border is now a 3 block tall wall of particles instead of a flat line on the
ground, so it is visible from any angle.**

## MaterialClaim_Block

- **Border height.** Each particle column is stacked `borders.height` blocks tall (default
  **3**), so the outline reads as a wall you can walk up to and see from a distance, rather
  than a line that disappears behind terrain or your own feet.
- The horizontal spacing is unchanged: a column every 3 blocks along the edge, the look
  that was requested.
- `borders.max-points` still caps the work per redraw. The cap now counts columns, not
  individual particles, so a tall wall does not silently thin the horizontal outline out.
  The total stays at 240 particles or fewer per redraw.
- The live **selection** outline stays flat (one block tall) on purpose: while you are
  dragging a box it is easier to read the shape as a line, and it keeps that preview cheap.

```yaml
borders:
  show-seconds: 30
  particle: END_ROD
  spacing: 3
  height: 3
  max-points: 240
```

The 30 second timer, right-click-in-air for the border, the settled preview and the
first-join welcome are unchanged.

## MaterialClaim_Chunk

Unchanged.

Needs Paper 1.21.1+ and Java 21+.
