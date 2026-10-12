# Stad

A NeoForge 1.21.1 mod: **build a town with citizens who live, work, love, gossip and remember**, like MineColonies
meets Minecraft Comes Alive, except every citizen is run by a small local AI you can talk to, by typing or with your
voice. Give them homes and jobs, plan buildings with your builder in plain words ("a small stone cottage with two
bedrooms and a chimney"), and lead them well, or they'll refuse your orders, strike, or move to a rival's town.

Talking, memory, voices and the AI runtime come from [They Will Talk](https://github.com/Eiriksb/they-will-talk),
which Stad requires. Everything runs locally on your own GPU. No cloud, no API keys.

> **Status:** early development (milestone M6). Found a town with a **town hall**; newcomers move in when there
> are free beds. Citizens sleep in their own beds, eat from the town hall, plan their day each morning (in character,
> with the local AI), spend evenings with friends (passing news along), court, marry and have children who grow up.
> Give them jobs from their card (right-click): **builder**, **farmer**, **lumberjack** and **courier**.
> **Plan buildings by talking to your builder**: "a small stone cottage with two bedrooms, a spruce roof and a
> chimney". They draw it up (restyling one of the pack's buildings when it fits, or designing a new one: framing,
> rooms, stairs, furniture, gable, hip or flat roofs, porches, chimneys), a see-through ghost of it appears on free
> land nearby with what it takes, and you change it as you talk ("make it two floors", "use dark oak for the roof",
> "put it next to the builder's hut") until you say "build it". Keep designs you like in **My pack**.
> **Lead them well**: your standing with the town rises and falls with how they're fed, housed and treated (and with
> what they make of your conversations). A respected leader is obeyed; a doubted one gets grudging, slower work; a
> resented one hears "no", with a reason, or "only once I've eaten". Miserable citizens leave, and an unhappy town
> goes on strike. Players have ranks, and every order is checked and logged. The design and roadmap are in
> [PLAN.md](PLAN.md).

### Talking a building through

Talk to the town's builder (typing or by voice) about what you'd like built. Behind the scenes two quick calls to the
local model sort out what you mean (a new design, a change, "build it", "move it", "keep it") and fill in a building
spec; the game turns the spec into blocks, so every design is buildable. Under the draft in chat there are buttons
for the same things: **Build it**, **Move here** (where you're looking), **Turn**, **Save** and **Drop**. Building it
is an order like any other: it needs an officer, and a builder who doesn't trust you may refuse.

![Houses the generator drew from six specs](docs/generated-houses.png)

(`tools/iso.py` draws structure files like this; the game tests write their sample houses to `run/gametest/stad-designs/`.)

**My pack** is an ordinary data pack in your world (`datapacks/stad_my_pack`): saved designs show up in the Building
Plans and can be copied to other worlds like any data pack.

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
| `/stad colony requests` | what the town needs, and where |
| `/stad colony standing` | how the town sees you as a leader, and why |
| `/stad colony setstanding <player> <value>` | admins, for testing |
| `/stad colony plan <building>` | officers: plan a building in front of you (what the Building Plans do) |
| `/stad colony cancel <id>` | officers |
| `/stad citizen job <uuid> <builder\|farmer\|lumberjack\|courier\|none>` | officers (the citizen card has buttons for it) |
| `/stad draft show` | your draft building: what it is and where (and its buttons again) |
| `/stad draft approve` / `here` / `turn` / `save` / `cancel` | what the buttons under a draft do |
| `/stad draft describe <text>` | draw up a building from a description without talking (asks the local model) |
| `/stad draft spec <json>` | draw up a building from a spec (see `planner/BuildSpec.java`) |
| `/stad colony found` / `list` | admins |
| `/stad citizen status <citizens>` | admins: what they're doing right now |
| `/stad citizen needs <citizens> <hunger> <rest>` | admins, for testing |

## Development

Requirements: JDK 21 (Gradle downloads it if needed), and a checkout of
[They Will Talk](https://github.com/Eiriksb/they-will-talk) next to this one (0.3.0-alpha.4 or newer, for its public
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
