**Fixes three bugs: the claim message saying "chunks" instead of blocks, the claim border
never disappearing after 30 seconds, and protection dyes being the wrong way round.**

## 1. "256 chunk(s)" after claiming one plot

The claim message, unclaim message, expiry warning and admin messages still used the old
chunk wording from the chunk-based plugin. A 16x16 claim is **256 blocks**, which is one old
chunk, so the message read `256 chunk(s)` and looked like the price and the size disagreed.

Now it says **blocks**, everywhere:

```
Land claimed. Your territory is now 256 blocks with 4d of upkeep.
```

## 2. The border never went away

`borders.show-seconds: 30` was being compared against a **count of task runs**, not ticks.
The task repeats every 10 ticks (half a second), so 30 "seconds" was really 30 x 10 ticks =
300 ticks = **5 minutes**. The outline looked like it was permanent.

It now uses a real clock deadline, so `show-seconds: 30` means exactly 30 seconds, and any
value you set is honoured.

## 2b. The shovel's cursor outline

The live outline shown while picking the second corner was **never stopped when you swapped
away from the shovel**. It kept running and redrawing wherever you looked, with any item in
hand, until you cancelled the selection or logged out.

It now also stops when you switch to another item, change world, or die.

## 3. Protection colours were inverted

The paid protection buttons showed **red dye while the protection was ON** and green while
it was off, and the item name was coloured to match the wrong state.

- **Green dye** = the protection is **on** and protecting.
- **Red dye** = it is **off**. An unbought protection is red too, since it is not protecting
  either; its description still says it can be bought for 24 hours.

The six free toggles follow the same rule: green when visitors are allowed, red when blocked.

## MaterialClaim_Chunk

Unchanged.

Needs Paper 1.21.1+ and Java 21+.
