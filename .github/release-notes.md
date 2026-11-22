**Settings fixes: managers, defaults, PvP, fire, animals, crops, merging, Bedrock menus.**

This release fixes things in the 0.3.x settings system that did not match how it was meant to work.

- **Managers can now do their job.** A manager can pay upkeep, buy and remove paid protections (paying the fee from their own inventory), and change free toggles, the territory name and the entry and leave messages. Managers can no longer add or remove trusted players: only the owner or an admin can. Managers and admins now see the Pay and Settings buttons in the menu.
- **Safe defaults.** A new territory starts with mob griefing, fluid flow and crop trampling blocked. Server claims (spawn, shops) start fully protected: no explosions, PvP, mob spawning or fire spread. Older server claims are upgraded once when the plugin starts.
- **PvP protection really works.** One control with four choices: everyone (no protection), visitors only, trusted only, nobody. Buying protection starts at nobody.
- **Fire protection is real.** Fire from lava, lightning and spread is only stopped in territories that bought fire protection.
- **Free toggles now do what they say.** Crop trampling, visitor animals and vehicles (including boats and minecarts) follow their toggles.
- **Mob spawn protection** only stops mobs that appear on their own, not spawn eggs, commands or other plugins.
- **Merging territories keeps every protection and purchase**, and the extra upkeep is counted correctly. New territories use the defaults from config.yml.
- **Overdue territories lose their unpaid protections straight away** (before, it only happened at the end of the grace period).
- **Bedrock friendly menus.** Trusted players and PvP are changed with normal taps, no shift-click.
- **Typing "cancel"** when asked for a name or message now cancels. Entry and leave messages are plain text, so they cannot carry click events.

Upgrading from 0.3.1 needs only the new jar. Territories already saved keep their current toggle values: open Settings and switch mob griefing, fluid flow and crop trampling to Blocked if you want the safe setting.

- Chest menu works on Java and Bedrock (Geyser).
- Needs Paper 1.21.1+ and Java 21+. These settings have not been tested on a live server yet: please open an issue if something misbehaves.
