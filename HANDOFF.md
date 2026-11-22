# MaterialClaim handoff (version 0.3.2)

Read this whole file first. It is the state of the project so work can continue in a new chat or on another Claude account.

## What this is
MaterialClaim: a Paper (1.21.1+, Java 21) land-claim plugin. Chunks that touch each other form one territory with one payment, one upkeep clock and one trust list. Claims are paid with items (copper, iron, gold, diamond, netherite), no economy plugin. The GUI works on Java and Bedrock (Geyser). Repo: Ahmed332009/MaterialClaim. Package dev.keepclaims (class names kept from the old KeepClaims name on purpose). GitHub Actions builds the jar and a v* tag publishes a Release.

## User and server context
- Paper 26.3, Java 25, Pterodactyl host, Geyser + Floodgate, offline-mode with a login plugin. Java and Bedrock players.
- The user works from an Android phone: short step-by-step instructions, no PC tools. A GitHub agent applies changes to the repo, so deliver patches plus an agent prompt.

## Design (decided by the user)
- Claim price 96 hours of item value (64 copper, 32 iron, 16 gold, 8 diamonds or 1 netherite ingot). Item values: copper 1.5, iron 3, gold 6, diamond 12, netherite ingot 96 (blocks 9x). max-prepaid-days 14, grace-days 7.
- Upkeep rate = chunks x group multiplier x (1 + 0.25 x active paid protections). An item worth v hours adds v chunk-hours to the pool. Size or protection changes keep the pool and recompute the remaining time.
- Unclaiming a chunk that would split a territory is blocked.
- Roles: visitor (not listed), builder, manager, owner, admin. Manager: pay upkeep, buy and remove paid protections (pays the fee), change free toggles, name and messages. Only owner/admin add or remove trusted players and unclaim. Admin can do everything everywhere for free.
- Paid protections (start off = vanilla, turning protection on costs a one-time fee of 24 hours of item value per protection per territory, remembered, plus +25% upkeep while active): explosion damage, PvP, mob spawning, fire spread. PvP is one control: everyone (no protection), visitors only, trusted only, nobody.
- Free toggles, all blocked by default: visitor doors/buttons/levers, visitor containers, visitor animals and vehicles, mob griefing, fluid flow in, crop trampling.
- Explosions are judged per block by the territory that owns the block. Server claims are fully protected. Unpaid protections switch off when a territory becomes overdue.
- Entry/leave messages and territory name (plain text), set from the menu (chat prompt, type cancel to abort) or commands. Commands: /claims flags, /claims flag <flag> <on|off>, /claims flag pvp-scope <everyone|visitors|trusted|nobody>, /claims flag name|entry-message|leave-message <text>, /claims trust <player> [builder|manager], /claims untrust <player>.

## Status and honesty notes
- 0.3.2 fixes a list of bugs found in an audit of 0.3.1 (see .github/release-notes.md). It has not been tested on a live server. GitHub Actions compiles it; the sandbox used for editing cannot reach the Paper API, so only parse checks were possible there.
- Territories saved before 0.3.2 keep their stored toggle values (mob griefing, fluid flow, crop trample were true by default in 0.3.0/0.3.1).

## Open ideas (only if the user asks)
- Fee that scales with territory size. A per-chunk option for the activation fee.
- Pagination for My claims and trust menus (capped at 45 entries).
- One-time migration to set the three safe defaults on already saved territories.
- DiamondClaims importer (format unknown; ask the user for a sample of its claims.yml).

## Rules for the next Claude
- Do not rename packages or classes. Write original code only (never copy DiamondClaims).
- Be honest about what is untested. Keep answers short and phone-friendly.
- When you deliver files, re-deliver an updated HANDOFF.md and a continuation prompt.
