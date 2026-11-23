**`/claims admin serverclaim` now gives you a netherite shovel to pick the land yourself**,
instead of only offering fixed-size squares.

## How it works now

1. Stand anywhere and run **`/claims admin serverclaim`**
2. You get a **netherite shovel named `Server Claim Tool`** in your hand, with instructions in
   its description
3. **Left click** a block for the first corner
4. **Right click** the opposite corner

That rectangle becomes **server land**, and **the shovel disappears**. Any size you like -
47x47, 100x37, 1x1 - the old fixed steps (31, 63, 95...) are no longer a limitation.

**Right click the air** to cancel; the tool is taken back. Switching to another item or
logging out also cancels.

## What it will not do

- **It never takes a player's land.** If someone's claim is inside your rectangle, their
  blocks are skipped and only the free land becomes server land. Their claim is untouched.
- **It does not duplicate an existing server claim.** Selecting land that touches server
  land merges into that claim rather than making a second one.
- **A selection covering only claimed land claims nothing** and tells you so.

## The old way still works

`/claims admin serverclaim <0-10>` still claims a centred square in chunks (radius 0 = 31x31,
10 = 351x351), so existing habits and scripts keep working. Tab-complete offers the numbers.

## MaterialClaim_Chunk

Unchanged.

Needs Paper 1.21.1+ and Java 21+.
