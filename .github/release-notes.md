- Land claims paid with items instead of money: copper, iron, gold, diamond, netherite.
- Connected chunks work as one territory: one payment, one upkeep clock, one trust list.
- Claim price 96 hours of items (64 copper, 32 iron, 16 gold, 8 diamonds or 1 netherite ingot).

**New in this version: per-territory settings.**

- Paid protections (explosion damage, PvP, mob spawning, fire spread) start on. Turning one off protects the territory and costs an activation fee plus a permanent upkeep increase per cycle.
- Free toggles: visitor doors, containers and animals, mob griefing, fluid flow, crop trampling.
- Custom territory name and entry/leave messages.
- PvP scope: everyone, visitors, trusted or nobody.
- Three trust levels: visitor, builder, manager.
- Settings GUI, plus `/claims flag <flag> <value> [territory]` and `/claims flags [territory]`.
- When a territory runs out of upkeep its paid protections are dropped first, before the territory is ever removed.

- Chest menu works on Java and Bedrock (Geyser).
- Needs Paper 1.21.1+ and Java 21+. These settings are new and have not been tested on a live server yet: please open an issue if something misbehaves.
