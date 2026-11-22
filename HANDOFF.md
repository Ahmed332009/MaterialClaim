# MaterialClaim handoff (version 0.2.0)

Read this whole file first. It is the complete state of the project so work can continue in a new chat or on another Claude account.

## Name
The plugin is called MaterialClaim (renamed from KeepClaims in 0.1.1). The GitHub repo is Ahmed332009/MaterialClaim. Only the player-visible names changed: plugin name, jar name, data folder plugins/MaterialClaim, permission nodes materialclaim.use, materialclaim.admin, materialclaim.limit.<n>. The Java package dev.keepclaims and the class names (KeepClaims, ClaimManager, etc.) were deliberately NOT renamed, so no files had to move in the repo. A full internal rename can be done later if wanted.

## What the user wants
A custom land-claim plugin for their Minecraft server that replaces DiamondClaims (Hangar, by RealKnight, license unspecified, so DO NOT copy or patch its code).

Wanted features:
1. A chest-menu GUI (works for Bedrock players through Geyser).
2. Several payment materials with different upkeep time, instead of diamonds only.
3. "Claims that can be lost": upkeep runs out, a grace period, then the claim is removed.
4. No economy plugin and no Vault.
5. Sustainable: cannot be farmed for free, cannot bank unlimited time.

The user liked DiamondClaims' defaults (8 diamonds to claim, 2 per week upkeep, 3 day grace) and wants a more unique system.

## Server and user context
- Paper 26.3, Java 25, hosted on a Pterodactyl panel (Files, Terminal, Network tabs).
- Geyser-Spigot and Floodgate installed. Bedrock players use the "." name prefix. Java players also exist (some on a phone launcher).
- Offline-mode server with a login plugin, so UUIDs are offline UUIDs.
- The user works almost entirely from an Android phone/tablet browser: needs short step-by-step instructions, no PC tools, no command line.
- The user wants explanations that are complete but compact (mobile screen). No emojis in documents.

## Design decisions (already implemented, version 0.2.0)
- A "territory" is a group of connected chunks owned by one player. Connected = sharing a side (config connect-diagonals: true also joins corners). Claim.java IS the territory: owner, ownerName, created, paidUntil, trusted, chunks (Set of ChunkKey). ClaimManager keeps byChunk (ChunkKey to Claim) and the set of territories.
- One payment, one upkeep clock (paidUntil) and one trust list per territory. Claiming a chunk next to your territory joins it. A chunk touching two of your territories merges them (pools added, trusted unioned).
- Claim price: every new chunk costs initial-hours of item value (default 96 = 8 diamonds), whether it starts a territory or joins one. The price is exact in raw item value regardless of territory size.
- Upkeep model: a territory of n chunks drains rate = n * perChunkMultiplier(n) chunk-hours per hour. perChunkMultiplier comes from config group-upkeep (list of up-to / multiplier). The user asked for this to be configurable. Default is flat 1.0 (every chunk costs the same).
- An item worth v hours adds v chunk-hours to the pool. Credit in hours for the territory = v / rate. When the size changes the pool is kept and remaining time is recomputed (pool / newRate), capped by max-prepaid-days.
- PaymentService.charge(player, creditHours, multiplier, onlyMaterial): the "multiplier" argument is now the territory rate. It takes needRaw = creditHours * multiplier of item value, cheapest items first (or one chosen material), and returns credited = paidRaw / multiplier. Surplus from rounding up is credited, not lost.
- Unclaim: only allowed if the remaining chunks stay connected (otherwise message unclaim-split). No refund. The pool is kept, so remaining time is recomputed for the smaller territory.
- Expiry: after paidUntil + grace-days the whole territory is removed and logged to expired-claims.log. Server claims (owner null) never expire and never merge (one Claim per chunk).
- max-claims-default (and permission materialclaim.limit.<n>) now limits TOTAL chunks per player.
- Accepted items: values in config "materials" (user's current server values: copper ingot 0.25, iron ingot 0.5, gold ingot 1, diamond 12, netherite ingot 48, blocks are 9x). Only plain items count.
- max-prepaid-days default 14. grace-days 7.
- Old data: claims.yml used to be one entry per chunk under "claims:". On load, if there is no "territories:" key, the plugin converts the old format (groups connected chunks, sums remaining time into the pool) and saves a backup as claims-old-format.yml.
- Menus: main, pay, trust add, trust list, my claims (lists territories), unclaim confirm. Borders show the outline of the whole territory (edges only, within 96 blocks).
- Commands: /claims (aliases /claim, /kc). Admin: /claims admin bypass | serverclaim [radius] | unclaim (removes the whole territory) | reload. Permissions materialclaim.use, materialclaim.admin, materialclaim.limit.<n>.
- Data file: plugins/MaterialClaim/claims.yml now uses "territories:" with a list of chunk strings "worldUUID;x;z".

## Files (all in the repository root, flat layout so they can be uploaded from a phone)
ChunkKey.java, Claim.java, Util.java, PaymentService.java, ClaimManager.java, ProtectionListener.java, GuiManager.java, ClaimCommand.java, KeepClaims.java, plugin.yml, config.yml, build.gradle.kts, settings.gradle.kts, build.yml (GitHub Actions, must be placed at .github/workflows/build.yml in the repo), README.md, HANDOFF.md, CONTINUE_PROMPT.txt

## Status and honesty notes
- Bedrock menus work (the user tested version 0.1.x on the server). Version 0.2.0 territory changes are NOT yet tested.
- NOT compiled against the Paper API and NOT tested on a server. The sandbox cannot reach the Paper maven repository. The only check done: javac parse check, which showed no syntax errors (all errors were missing Bukkit classes, expected).
- Built against paper-api 1.21.11-R0.1-SNAPSHOT with api-version 1.21. It should load on Paper 26.3 but this is unverified.
- The user will build it with GitHub Actions (see README.md) and report errors back. Expect some compile errors on first build: fix them and re-send only the changed files.

## Open tasks, in priority order
1. Fix whatever the first real build reports.
2. Test territories on the server: claim, add an adjacent chunk, merge two territories, unclaim an edge chunk, try to unclaim a middle chunk (should be blocked), pay upkeep, expiry.
3. DiamondClaims migration (optional): write an importer for the old plugins/DiamondClaims/claims.yml. The file format is NOT known yet. Ask the user to paste about 20 lines of it before writing the importer. The uploaded jar was NOT decompiled.
4. Pagination for "My claims" and the trust menus (currently capped at 45 entries).
5. Optional: claim map in chat, BlueMap markers, adjacency rule, refund percent, per-claim trust levels (container only vs build), a raid or siege idea if the user wants claims to be lossable by force.
6. If the user asks, add a message file separate from config.yml.

## Rules for the next Claude
- Do not read or copy DiamondClaims' code. License is unspecified. Write original code only.
- Never claim the plugin is tested. Say plainly what is unverified.
- Keep answers short and phone-friendly.
- Whenever you deliver files, re-deliver an updated HANDOFF.md and CONTINUE_PROMPT.txt.
- Do not invent Paper API methods. If unsure about an API, say so and use the simplest well-known call.
