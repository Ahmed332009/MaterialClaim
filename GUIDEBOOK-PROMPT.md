# Prompt for writing the MaterialClaim player guidebook

Copy everything from **"START OF PROMPT"** to **"END OF PROMPT"** into the other AI.

---

## START OF PROMPT

You are writing a **player-facing guidebook** for a Minecraft server. Your readers are
ordinary players, not server admins and not programmers. They want to know what to type,
what to click, what it costs, and what happens if they do nothing.

### What the plugin is

The server runs **MaterialClaim** (specifically the `MaterialClaim_Block` variant). It lets
players claim land and then keep it alive by paying upkeep with **ordinary Minecraft
items** instead of money. There is no economy plugin, no Vault, no coins. A diamond is
literally worth 12 hours of claim time.

Claiming uses a **golden shovel**, the same way GriefPrevention does.

### Non-negotiable writing rules

1. **Write for a player, not an admin.** Never mention `config.yml`, permission nodes,
   Java, YAML, source files, or plugin internals. If a number comes from config, just state
   the number as fact ("claims cost 96 hours of upkeep").
2. **Never invent a command that is not in this brief.** A made-up command is the single
   worst thing you can do here, because players will type it, it will fail, and they will
   think the server is broken. If you are unsure, leave it out.
3. **Never invent a price, a number of hours, or a percentage.** Use only the numbers given
   below. Do not round them into different numbers.
4. **Do not promise anything about refunds or safety.** There are **no refunds, ever**, and
   unclaimed or expired land does **not** return items or time. Say this plainly.
5. Prefer short paragraphs and step-by-step numbered lists over long prose. Players skim.
6. Use exact in-game wording where it is given in quotes, because that is what players will
   see on their screen.

### The golden shovel: how claiming works

- **Left click** a block with a golden shovel: sets the **first corner**. A particle
  outline follows your cursor.
- **Right click** a block: sets the **second corner**. A **price menu opens automatically**
  and shows the exact size in blocks and the price before anything is charged.
- The menu has a **Confirm** button. Nothing is charged until the player confirms.
  **Cancel** charges nothing.
- The claim covers the **whole vertical column** at those coordinates, from bedrock to
  build limit. It is not a box; you cannot claim "only the surface".
- The claim is a **rectangle**, so pick two opposite corners.

**Selection tips worth telling players:**
- The outline while selecting is a thin flat line on the ground; the finished claim border
  is a taller wall of particles. This is intentional, not a bug, so do not describe it as one.
- **Click the air with the shovel to cancel** a selection you started by mistake.
- A too-small selection is refused and nothing is charged.
- The claim must be at least **100 blocks** (about 10x10). Anything smaller is refused with
  a message that says the minimum.
- Land already claimed by someone else cannot be selected.
- Land you already own can be included, and you **only pay for the new blocks**, so
  expanding your own claim is cheap.

### The stick

- **Right click a block with a stick** to inspect it. It tells you who owns that land, how
  big the claim is, and how much upkeep time is left.
- On unclaimed land it says so. On server-protected land (spawn, shops) it says the server
  owns it.

### Seeing your border

- Stand inside your own claim and **right click the air with the golden shovel**. The claim
  border is outlined with particles for **30 seconds**. You also get a chat line naming the
  claim.
- If you stand on unclaimed land and do this, it tells you how to claim instead.
- There is also a **Show borders** button inside the claim menu.

### The claim menu

- **`/claim`** opens the claim menu. **`/claims`** and **`/kc`** are exactly the same
  command; you can use whichever is easiest to type.
- **With no extra words**, `/claim` opens the menu for the land you are standing on:
  - On unclaimed land, it explains the shovel and offers buttons to **get a golden shovel**
    and **get a stick**.
  - On your own land, it opens the management menu: pay upkeep, trusted players, unclaim,
    settings, show borders, and a list of all your claims.
- Players are never required to use chat commands; everything important is a button in the
  menu. Present the menu as the normal way and the commands as shortcuts.

### Every command a normal player can use

These are the **only** player subcommands. All of them start with `/claim` (or `/claims`
or `/kc`).

| Command | What it does |
| --- | --- |
| `/claim` | Opens the claim menu for the land you are standing on |
| `/claim menu` | Same as `/claim` |
| `/claim shovel` | Gives you a golden shovel (also a button in the menu) |
| `/claim stick` | Gives you a stick (also a button in the menu) |
| `/claim pay` | Opens the upkeep payment screen for the claim you are standing in |
| `/claim list` | Opens the list of all your claims; click one to manage it |
| `/claim borders` | Outlines the border of the claim you are standing in |
| `/claim info` | One chat line: who owns this land, its size, and its upkeep status |
| `/claim flags` | Lists every protection setting for this claim and its current state |
| `/claim flag <setting> <on/off>` | Turns one protection setting on or off |
| `/claim flag pvp-scope <mode>` | Sets who may PvP in your claim (see below) |
| `/claim flag name <text>` | Renames the claim. Use `clear` to remove the name |
| `/claim flag entry-message <text>` | Message shown to players entering. `clear` to remove |
| `/claim flag leave-message <text>` | Message shown to players leaving. `clear` to remove |
| `/claim trust <player>` | Lets a player **build** in your claim |
| `/claim trust <player> builder` | Same as above |
| `/claim trust <player> manager` | Manager: can also pay upkeep, buy protections, change settings |
| `/claim untrust <player>` | Removes that player's access |
| `/claim unclaim` | Gives up the whole claim you are standing in |

Notes for accuracy:
- `/claim flag pvp-scope` accepts **`everyone`**, **`visitors`**, **`trusted`** or
  **`nobody`**. `everyone` means no PvP protection at all.
- `/claim flag` accepts `on`/`off` (also `true`/`false`, `yes`/`no`, `allow`/`block`).
- Trust levels are exactly **builder** and **manager**. There is no "visitor" trust to give
  someone; visitors are simply players who are not trusted.
- Do **not** document `/claims admin ...`. It is for server staff only. You may mention in
  one line that staff have separate tools that are not covered here.

### Claiming costs: how the price is calculated

- A claim costs upkeep time based on area. The reference point: **256 blocks (a 16x16
  square) costs 96 hours**, which is **8 diamonds**.
- That works out to about **0.375 hours per block**. A 10x10 (100 block) minimum claim
  costs about **37.5 hours**.
- Price is **proportional to area** on this server: double the area, double the price.
- Specifically: **`new blocks × 0.375 hours`**. If you expand your own claim, only the new
  blocks are charged.
- The price menu always shows the exact number before you confirm, so tell players they can
  always look instead of calculating.

Do **not** give a table of "size → diamonds" beyond simple examples, and do not state the
per-block price as a promise that can never change; say "on this server" or "currently".

### Paying: which items are accepted, and what they are worth

Prices are in **hours of upkeep**. These are the accepted items and their exact values:

| Item | Hours of upkeep |
| --- | --- |
| Copper ingot | 1.5 |
| Copper block | 13.5 |
| Iron ingot | 3 |
| Iron block | 27 |
| Gold ingot | 6 |
| Gold block | 54 |
| Diamond | 12 |
| Diamond block | 108 |
| Netherite ingot | 96 |
| Netherite block | 864 |

Important payment rules to state clearly:
- **Only plain items count.** Renamed or enchanted items are **ignored** when paying. This
  catches players out, so say it explicitly.
- Blocks are worth **9x** the matching ingot.
- A **netherite block is worth 864 hours**, the best value per inventory slot.
- Players can choose **which item to pay with** in the payment screen, or leave it on
  **Automatic**, which spends their **cheapest** accepted items first.

### The upkeep clock: the part players must understand

This is the core of the plugin and the part most players get wrong. Be very clear.

- A claim has an **upkeep clock**. It counts down in real time, every second, whether the
  player is online or not.
- Running out of time is not instant death: the claim becomes **Overdue** and keeps
  protecting for **7 more days**. Then it is **deleted** and the land becomes unclaimed.
- Players are warned when a claim has **less than 3 days** left, and get a warning on join.
- The maximum time a claim can hold banked is **14 days**. Extra payment beyond that is
  refused, and the items are **not** taken.
- **Nothing is refunded, ever.** Not when you unclaim, not when a claim expires, not when
  you shrink it.

How the clock drains — state this simply:
- A **256 block claim drains 1 hour of upkeep per hour**. So 96 hours of upkeep lasts
  about **4 days** for a 16x16 claim.
- **Bigger claims drain faster**, in direct proportion to area. A 512 block claim drains
  **2 hours per hour**, so the same 96 hours lasts about 2 days.
- The **paid protections** below make the clock drain faster too (up to **2x**). That is the
  hidden cost of buying protections, and players must be told.

### Paid protections (the four purchases)

There are four protections a claim can **buy once**. Buying one is optional.

1. **Explosion Damage Protection** — TNT, creeper and wither block damage
2. **PvP Protection** — player versus player combat
3. **Mob Spawning Protection** — hostile mob spawning inside the claim
4. **Fire Spread Protection** — fire spreading to or from claim blocks

What buying one costs and does — get this exactly right:

- **Cost: 24 hours of that claim's own upkeep clock.** Example: a claim has `3d 23h` left;
  after buying one protection it has `2d 23h`. The 24 hours are taken off the clock, not
  from the player's inventory.
- If the claim has **less than 24 hours** left, the purchase is **refused** with a message
  telling you to pay upkeep first. Nothing is charged.
- Before it is bought, a **confirmation screen** appears. It says the claim will lose
  **24 hours**, that the upkeep cost **rises permanently**, and that **nothing is refunded**.
  Confirming buys it. Cancelling charges nothing.
- **Every purchase permanently raises that claim's upkeep cost by 25%.** This stacks, and it
  **stays** even if you later turn the protection off. Owning all four makes upkeep cost
  **2x**, so:
  - 0 bought: a diamond (12h) is worth **12 hours** of that claim's time
  - 1 bought: **9.6 hours**
  - 2 bought: **8 hours**
  - 3 bought: **6.9 hours**
  - 4 bought: **6 hours**
- **Toggling a bought protection on or off is free.** No hours are taken, none are given
  back, and the cost does not go down. Once bought, it is yours forever.
- **Buying is one-time and there are no refunds**, including if the claim later expires.
- A claim that runs out of upkeep has its protections **switched off** but **keeps them
  bought**, so paying upkeep brings them back on for free.
- **PvP is special.** Buy PvP once for 24 hours, then changing who may fight is free
  forever. The options are `everyone` (no protection), `visitors`, `trusted`, `nobody`.

Servers staff note: anyone can buy, including staff. There is no free pass.

### Free toggles (these never cost anything)

These six settings are **free** and can be switched on and off as often as the player likes.
Explain them as visitor permissions — what visitors are **allowed** to do in your claim.

| Setting | What it controls |
| --- | --- |
| Visitor Doors, Buttons and Levers | Visitors may use doors, buttons and levers |
| Visitor Containers | Visitors may open chests, barrels and similar |
| Visitor Animals and Vehicles | Visitors may harm, leash or ride animals |
| Mob Griefing | Endermen, withers and similar may break blocks |
| Fluid Flow | Fluids from neighbouring chunks may flow in |
| Crop Trampling | Visitors may trample crops |

Be accurate about defaults on a new claim: the four paid protections start **off**, and the
six free toggles start in a **safe** state (visitors are blocked from doors, containers and
animals; mob griefing, fluid flow and crop trampling are blocked). Tell players to check the
Settings screen rather than assuming.

There are also **public blocks** that **anyone can always use inside any claim**, even one
that is not theirs. List these, as players ask constantly:

Crafting table, Ender chest, Enchanting table, Anvil, Chipped anvil, Damaged anvil,
Grindstone, Loom, Cartography table, Stonecutter, Smithing table.

### Paying upkeep: the payment screen

- **`/claim pay`**, or the **Pay upkeep** button in the claim menu.
- Buttons: **+1 day**, **+3 days**, **+7 days**, **+14 days**, **Fill to maximum**. Each
  button shows the cost in items before you click.
- The player can pick which item to pay with, or use **Automatic** (cheapest first).
- Payment covers the **whole claim**, not one chunk of it.
- If a payment would exceed the 14 day maximum, it is refused and items are **not** taken.

### Trusted players

- **Builder** — can build, break and use everything in the claim.
- **Manager** — everything a Builder can do, **plus** pay upkeep, buy protections and change
  the free settings. A Manager **cannot** change who is trusted.
- Only the **owner** can trust, untrust, unclaim, and rename the claim.
- Trust is per **claim**. If you own two separate claims, trusting someone in one does not
  give them the other.

### Things that are easy to get wrong — cover these explicitly

Write a short "common mistakes" section covering at least:

1. **Renamed or enchanted items do not count** as payment.
2. **Upkeep drains even while you are offline.** A claim can expire while you are away.
3. **Unclaiming refunds nothing.** Neither does letting a claim expire.
4. **Buying a protection costs time from your claim, not items from your inventory**, and it
   makes your upkeep drain faster forever.
5. **Turning a bought protection off does not make your upkeep cheaper.**
6. **Your claim is a vertical column, not a box.** You cannot claim only the surface.
7. **You cannot claim land someone else already owns**, but you can extend your own, and you
   only pay for the new blocks.
8. **A manager cannot remove people's access** — that is owner-only.

### Structure the guidebook like this

1. A one-paragraph welcome: what this server is and that land is claimable.
2. **How to claim your first piece of land** — the shovel, step by step, with a worked
   example (a 16x16 claim costs 96 hours, which is 8 diamonds).
3. **What it costs and how to pay** — the item value table, the plain-items rule, the pay screen.
4. **Keeping your claim alive** — the clock, 7 day grace, 3 day warning, 14 day cap.
5. **Protecting your claim** — the four paid protections, the 24 hour cost, the 25% rise and
   the 9.6/8/6.9/6 table, and the free toggles.
6. **Working with other players** — builder vs manager, public blocks, PvP options.
7. **Command cheat sheet** — the table above, nothing more.
8. **Common mistakes.**
9. A short FAQ answering: *Does it expire if I'm offline?* *Can I get a refund?* *Can I
   claim just the surface?* *Why did my payment not work?* *Does turning a protection off
   make it cheaper?*

### Tone and format

- Friendly, plain English, second person ("you"). No developer jargon.
- Headings and bullet lists; keep each section skimmable.
- Markdown.
- Keep the whole guidebook readable in one sitting. Depth matters less than accuracy.

### Final check before you finish

Re-read your draft against this list and fix anything wrong:

- [ ] Every command appears in the command table above, and **no** command that is not there.
- [ ] No invented numbers. The figures 96 hours, 24 hours, 25%, 2x, 12/9.6/8/6.9/6, 7 days,
      3 days, 14 days, 100 blocks, 256 blocks, 0.375 hours per block, and the ten material
      values all match this brief exactly.
- [ ] It says clearly that there are **no refunds** and that **renamed/enchanted items do
      not count**.
- [ ] It never mentions `config.yml`, permissions, or `/claims admin`.
- [ ] Builder and Manager are described correctly, including that a Manager cannot change
      who is trusted.
- [ ] The 24 hour purchase cost is described as taken from the **claim's clock**, not from
      the player's inventory.

## END OF PROMPT

---

## Notes for you (not part of the prompt)

A few facts the other AI was deliberately **not** given, because they are yours to decide:

- **Prices are configurable.** If you change `claim-price.per-chunk-hours` or switch
  `mode: tiered`, the numbers in this prompt (96 hours per 256 blocks, 0.375 per block)
  become wrong. Tell me and I will regenerate that section.
- **`claim-price.minimum-blocks: 100`** is the 100-block minimum quoted above.
- **Upkeep/protection rates** quoted: `paid-protection-upkeep-multiplier: 0.25` (the 25%),
  `max-prepaid-days: 14`, `grace-days: 7`, `warn-days: 3`.
- **`max-claims-default: 25`** (25 chunks' worth of blocks, i.e. 6,400 blocks per player) is
  a hard limit the guide does not mention. It is enforced, so consider adding a line about
  it, or tell me to include it.

While writing this I found and fixed a real bug: the block plugin's `config.yml` was
**missing** `max-prepaid-days`, `grace-days`, `warn-days` and `check-interval-minutes`. The
code has always read them with those defaults, so behaviour was correct, but you could not
have tuned them. They are now present and documented.
