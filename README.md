# Stad

A NeoForge 1.21.1 mod: **build a town with citizens who live, work, love, gossip and remember**, like MineColonies
meets Minecraft Comes Alive, except every citizen is run by a small local AI you can talk to, by typing or with your
voice. Give them homes and jobs, plan buildings with your builder in plain words ("a small stone cottage with two
bedrooms and a chimney"), and lead them well, or they'll refuse your orders, strike, or move to a rival's town.

Talking, memory, voices and the AI runtime come from [They Will Talk](https://github.com/Eiriksb/they-will-talk),
which Stad requires. Everything runs locally on your own GPU. No cloud, no API keys.

> **Status:** early development (milestone M3). You can found a town with a **town hall** (sneak + right-click
> opens its storage). Newcomers move in when there are free beds, and you can invite citizens you meet (right-click
> them). Citizens sleep in their own beds, eat from the town hall and gather there in the evening. Make one of them
> the **builder** (right-click), use the **Building Plans** on the ground to plan a building from the bundled
> "Medieval" pack (builder's hut, cottage, warehouse), and they clear the site and build it block by block with
> materials from the town hall, telling you what's missing. Ask them how it's going. Players have ranks, and every
> order is checked and logged. The design and roadmap are in [PLAN.md](PLAN.md).

### Building packs

A pack is part of a data pack: a manifest per building in `data/<namespace>/stad/buildings/<pack>/<building>.json`
naming a vanilla structure template (`data/<namespace>/structure/...nbt`, saved with a structure block, front door
facing north). See `data/stad/stad/buildings/medieval/` and `tools/packgen.py`, which generates the bundled pack.

```json
{ "type": "stad:home", "name": "Cottage", "description": "A cozy home with two beds.",
  "structure": "stad:medieval/cottage", "tags": ["small", "home"] }
```

### Commands

| Command | |
|---|---|
| `/stad colony info` | the town you're in (or lead): citizens, beds, ranks |
| `/stad colony rename <name>` | officers |
| `/stad colony rank <player> <owner\|officer\|member\|visitor>` | the founder |
| `/stad colony dissolve` | the founder (or an admin) ends the town |
| `/stad colony builds` | planned buildings and how they're going |
| `/stad colony plan <building>` | officers: plan a building in front of you (what the Building Plans do) |
| `/stad colony cancel <id>` | officers |
| `/stad citizen job <uuid> <builder\|none>` | officers (the citizen card has buttons for it) |
| `/stad colony found` / `list` | admins |
| `/stad citizen status <citizens>` | admins: what they're doing right now |
| `/stad citizen needs <citizens> <hunger> <rest>` | admins, for testing |

## Development

Requirements: JDK 21 (Gradle downloads it if needed), and a checkout of
[They Will Talk](https://github.com/Eiriksb/they-will-talk) next to this one (0.3.0-alpha.3 or newer, for its public
API). TWT in turn needs [Sipher](https://github.com/Eiriksb/Sipher) built next to it
(see TWT's README).

```
Projects/
  Sipher/           ./gradlew assemble
  they-will-talk/
  Stad/             this repository
```

Stad includes TWT as a Gradle composite build (`settings.gradle`), so changes in either repository are built together.
Point it elsewhere with `-PtwtDir=<path>`.

```bash
./gradlew :mod:build                 # build
./gradlew :mod:runGameTestServer     # in-game tests (headless)
./gradlew :mod:runServer             # dev server (TWT dashboard on http://127.0.0.1:8765)
./gradlew :mod:runClientJoin         # a dev client that joins it as "Eirik"
./gradlew :mod:runClientJoin2        # a second one as "Tester", for multiplayer
scripts/rcon.py "list"               # talk to the dev server (enable RCON in run/server/server.properties)
```

The dev runs share TWT's AI runtime (`../they-will-talk/runtime`), so models installed for TWT work here too. The dev
server needs `online-mode=false` in `run/server/server.properties` for the offline dev clients to join.

## License

MIT
