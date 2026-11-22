# MaterialClaim

Chunk claims paid with items. No economy plugin needed. Includes a chest-menu GUI that also works for Bedrock players through Geyser.

STATUS: GUI tested on Java and Bedrock in version 0.1.x. Version 0.2.0 territories are new and untested.

## How players use it
- /claims opens the menu (also /claim and /kc).
- Chunks that touch each other (sides) form ONE territory: one payment, one upkeep clock, one trust list. Upkeep grows with the number of chunks.
- You can only unclaim a chunk if your territory stays connected.
- Stand in a chunk and click "Claim this chunk". You pay with copper, iron, gold, diamond or netherite (or their blocks).
- "Pay upkeep" adds time. Pick which item to pay with, or leave it on Automatic (cheapest first).
- "Trusted players" lets friends build in your claim.
- When upkeep runs out the claim keeps protecting for a grace period, then it is removed.

## Build it from a phone (GitHub Actions)
1. Unzip the download in your Files app.
2. On github.com create a new repository (public or private).
3. Tap Add file, then Upload files. Select every file from the unzipped folder and commit.
4. Tap Add file, then Create new file. Name it exactly: .github/workflows/build.yml
   Open build.yml from the unzipped folder, copy all of its text and paste it in. Commit.
5. Open the Actions tab. The "Build MaterialClaim" run starts by itself (or tap Run workflow).
6. When it shows a green tick, open the run and download the artifact "MaterialClaim-jar". Unzip it to get MaterialClaim-0.2.0.jar.
7. If the run is red, open it, copy the error text and send it to Claude.

## Install on the server
1. Stop the server.
2. Upload MaterialClaim-0.2.0.jar to the plugins folder.
3. Do NOT run it together with DiamondClaims for long: both use the /claim command and both protect chunks. Your old DiamondClaims claims are not imported yet.
4. Start the server and edit plugins/MaterialClaim/config.yml if you want different prices.
5. After editing, run /claims admin reload.

## Admin
- /claims admin bypass  toggles building anywhere
- /claims admin serverclaim [radius]  protects chunks for the server (spawn, shops)
- /claims admin unclaim  removes the claim you are standing in
- Permission materialclaim.limit.<number> raises a player's claim limit
