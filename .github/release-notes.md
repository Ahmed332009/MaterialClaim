**Fixes a successful claim reporting "you already own this land", and stops a double click on
Confirm from claiming twice.**

## The bug

Clicking **Confirm** twice (a normal double click) sent two confirms for the same rectangle.
The first click claimed the land correctly. The second click was re-priced, found nothing new
to claim, and reported **"You already own this chunk"** even though the claim had just
succeeded. On a brand new account that reads as "claiming does not work", while the land was
in fact claimed.

- A repeat confirm for a rectangle the same player **just** claimed is now ignored outright,
  for 10 seconds, so a double click or a click that lands a tick later changes nothing.
- The guard is per rectangle, not per player, so claiming a different plot immediately
  afterwards works normally.
- If the duplicate click arrives, the claim menu is reopened instead of printing a warning,
  so the player sees the claim they just made.

## Also in this release

- **Console log for every claim**, so it can be verified from the server log:
  `Claim by Steve: 0,0 to 15,15 (256 new blocks, 256 total) cost 96h, 4d left`.
- The `already-yours` message no longer says "chunk" and no longer implies a failure:
  *"You already own all of that land, so there is nothing to pay."* `already-claimed` likewise
  says "land" rather than "chunk".

## MaterialClaim_Chunk

Unchanged.

Needs Paper 1.21.1+ and Java 21+.
