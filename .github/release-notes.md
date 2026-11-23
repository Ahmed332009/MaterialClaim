**Selection particles thinned out and settled, so the outline stays a line instead of
turning into a cloud while you walk. 0.4.2's border, timing and welcome all stay.**

## MaterialClaim_Block

- **About 91% fewer particles per redraw.** The outline is now a single layer of points
  every 3 blocks along the edge, the spacing the old border used, instead of two particles
  per block stacked at ground and eye height. A 48x48 selection went from 776 points per
  redraw to 65.
- **The live outline settles instead of endlessly redrawing.** Every redraw used to leave a
  fresh generation of particles behind, so moving while selecting piled generations on top
  of each other until the line looked like fog. The outline now redraws the moment the
  cursor moves to a new block, then only refreshes on a slow heartbeat so the line stays
  visible without accumulating.
- **Hard cap on particles per redraw** (`borders.max-points`, default 240) so a very large
  claim cannot flood the screen.
- `borders.spacing` replaces `borders.density`: 3 means a particle every 3 blocks. The old
  `density` key is still read if you had set it, so existing configs keep working.

The 30 second Show borders timer, the bright END_ROD particles, right-click-for-border and
the first-join welcome are unchanged.

## MaterialClaim_Chunk

Unchanged.

Needs Paper 1.21.1+ and Java 21+.
