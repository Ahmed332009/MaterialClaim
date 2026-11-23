**Adds the upkeep clock settings to `config.yml`, which were read by the code but missing
from the file, and a prompt for writing the player guidebook.**

## MaterialClaim_Block

- `config.yml` now contains `max-prepaid-days` (14), `grace-days` (7), `warn-days` (3) and
  `check-interval-minutes` (5). The plugin has always used these values, so **behaviour is
  unchanged**; they were simply absent from the file, so they could not be tuned. They are
  now documented in place.

No gameplay changes. The golden shovel, borders, particles, protections and welcome are
untouched.

## Repository

- `GUIDEBOOK-PROMPT.md`: a complete, verified brief for writing a player-facing guidebook.
  Every command, price, material value and timer in it was checked against the shipped code.

## MaterialClaim_Chunk

Unchanged.

Needs Paper 1.21.1+ and Java 21+.
