# Stad

A NeoForge 1.21.1 mod: **build a town with citizens who live, work, love, gossip and remember**, like MineColonies
meets Minecraft Comes Alive, except every citizen is run by a small local AI you can talk to, by typing or with your
voice. Give them homes and jobs, plan buildings with your builder in plain words ("a small stone cottage with two
bedrooms and a chimney"), and lead them well, or they'll refuse your orders, strike, or move to a rival's town.

Talking, memory, voices and the AI runtime come from [They Will Talk](https://github.com/Eiriksb/they-will-talk),
which Stad requires. Everything runs locally on your own GPU. No cloud, no API keys.

> **Status:** 0.1.0, the first release (all eleven milestones of [PLAN.md](PLAN.md) done). New players: start with the
> [guide](docs/GUIDE.md); what's in this release is in the [changelog](CHANGELOG.md).
>
> Found a town with a **town hall**; newcomers move in when there
> are free beds. Citizens sleep in their own beds, eat from the town hall, plan their day each morning (in character,
> with the local AI), spend evenings with friends (passing news along), court, marry and have children who grow up.
> Give them jobs from their card (right-click): **builder**, **farmer**, **lumberjack**, **courier**, **guard**, **cook**,
> **fisher**, **shepherd** and **miner**.
> **Plan buildings by talking to your builder**: "a small stone cottage with two bedrooms, a spruce roof and a
> chimney". They draw it up (restyling one of the pack's buildings when it fits, or designing a new one: framing,
> rooms, stairs, furniture, gable, hip or flat roofs, porches, chimneys), a see-through ghost of it appears on free
> land nearby with what it takes, and you change it as you talk ("make it two floors", "use dark oak for the roof",
"put it next to the builder's hut", "facing the lake") until you say "build it". Keep designs you like in **My pack**.
> **Make the town your own**: stand in a house you built and tell a citizen "this is your home now"; they look round
> and move in, or tell you what's missing ("I'd need a bed in here"). Scan your buildings into My pack with the
> **scan wand**, fix what the game guessed about their blocks, and build any pack building in another **style**
> (Nordic, Desert, Stone, Brick...). When the builder runs out of spruce they ask before using birch instead.
> **Defend it**: raiders that fit the land (Norse raiders, marauders, bandits or pillagers) come for bigger towns at dusk; someone spots them and shouts a warning, guards fight, the rest
> shelter, and the builders repair the damage afterwards. Towns that choose to be **open to war** can raid each other
> (with warnings, raid windows and a banner to take at the town hall), lure away each other's unhappy citizens, and
> make peace through envoys who argue for it in character.
> **Lead them well**: your standing with the town rises and falls with how they're fed, housed and treated (and with
> what they make of your conversations). A respected leader is obeyed; a doubted one gets grudging, slower work; a
> resented one hears "no", with a reason, or "only once I've eaten". Miserable citizens leave, and an unhappy town
> goes on strike. Players have ranks, and every order is checked and logged. The design and roadmap are in
> [PLAN.md](PLAN.md).

### Talking to citizens

Right-click a citizen to talk to them face to face: the chat screen shows who they are (job, town, age, home), how
they're doing, what they think of you, and what you've said to each other. Type to them there, or speak with your
voice chat key while looking at them (spoken lines and their replies show up in the screen too). It also has buttons
for the orders you may give (a job, the house you're standing in, inviting them, asking them to leave) and, for the
builder, a shortcut to plan a building.

Every citizen has their **own voice**, from their genes and age: a pitch, a pace, a timbre and a family way of speaking,
passed on to their children, so families sound alike. A child's voice changes as they grow up; otherwise it stays the
same, whatever their job or town. (With They Will Talk's designed voices, each is described in words, like "A young
woman in her twenties with a medium-pitched, crisp voice and a gentle sing-song cadence"; `/stad citizen voice` shows
them.) When you talk to the builder aloud, they keep it short and leave the lists to the screen.

### Jobs and food

| Job | Works | Needs |
|---|---|---|
| Builder | builds what officers plan | the materials in the town hall |
| Farmer | harvests and replants | farmland |
| Lumberjack | fells natural trees and replants | trees |
| Courier | carries food and materials to the town hall and the builder | a warehouse |
| Guard | patrols and fights, trained (30 health, harder blows) | a sword from the town's stores |
| Cook | bread from wheat; cooked meat, fish and potatoes, to the town hall | a furnace, smoker or campfire |
| Fisher | fishes from the shore (the game's fishing loot) | a pond or river |
| Shepherd | shears, breeds up to `herdSize` of each kind, butchers the extras | farm animals (named or leashed ones are pets) |
| Miner | digs a 5x5 shaft deeper, layer by layer, down a spiral of steps; walls off water and lava | a Mine (Medieval pack) |

All of it happens on the town's land. Wheat only feeds anyone once a cook bakes it, and cooked food fills far more than
raw, so a farmer and a cook keep a small town fed.

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

### Your own buildings

**Adopting a house.** Stand inside a building you made and tell a citizen "this is your home now" (or "this is your
workshop", "...your warehouse"), or use `/stad adopt`. The game fills the rooms from where you stand: doors that lead
outside are the way in, gaps in the walls and open sky inside are reported, and what's in there is counted. A home
needs walls, a roof, a door and a bed; a builder's hut a crafting table, a chest and a bed; a warehouse four chests or
barrels; a workshop a crafting table and a workstation. If it has them it becomes one of the town's buildings, as it
stands, and the citizen moves in (an order like any other: a resented leader may hear "no"). If not, they tell you
what's missing.

**The scan wand** (an amethyst shard on top of two sticks): left-click one corner of a building, right-click the
opposite one, then sneak and use it. The **pack editor** opens: name it, say what kind of building it is, and check
what each kind of block is for. The game guesses every block's role (floor, walls, frame, roof, trim, windows, doors,
lights, furniture, decoration) from its shape and where it sits; click a row to fix a wrong guess. Save, and it's in
My pack and the Building Plans, turned so its door faces north like every pack building. Vanilla structure blocks in
Data mode with `stad:storage`, `stad:work`, `stad:sit`, `stad:bed`, `stad:door` or `stad:shaft` as their metadata are kept
as markers (with storage markers, only the chests next to them count as the town's; `stad:shaft` is the middle of a
mine's shaft).

**Styles.** A palette gives the roles new materials, so any pack building can be built in another style: pick one in
the Building Plans, say it to the builder ("in the Nordic style"), or `/stad colony plan <building> <style>`. The mod
brings seven; add your own as `data/<namespace>/stad/palettes/<id>.json`:

```json
{ "name": "Nordic", "description": "Dark timber and steep dark roofs.",
  "walls": "spruce", "frame": "dark_oak", "roof": "dark_oak", "floor": "spruce", "trim": "cobblestone" }
```

![The Medieval cottage in its own materials and in the seven styles](docs/palettes.png)

**Packs.** Besides Medieval (cottage, builder's hut, warehouse, kitchen, mine) the mod brings **Fjord** (laid log walls
on stone footings under turf roofs) and **Oasis** (sandstone with flat roofs and parapets), each with a home, a
warehouse and a builder's hut.

![The Fjord and Oasis packs, and the Medieval kitchen and mine](docs/packs.png)

**Making do.** When the builder runs out of a material and the town has enough of a similar one (another wood for
wood, another stone for stone), they ask before swapping: "We're out of oak. Shall I use birch for the walls?" Say
yes or no to them, or click the buttons in chat.

**Sharing packs.** `/stad pack export [name]` zips My pack into `stad/exports/`. It's a data pack: drop it in a
world's `datapacks` folder, or in `stadpacks/` in the game folder to have it in every world.

### Raids and rivalry

**Guards** patrol between the town hall, watchtowers and barracks, and fight monsters on the town's land and raiders.
They take the best sword and armour from the town's stores (and the town asks for a sword when there's none). Brave
sorts (boastful or dramatic citizens) pick up a weapon when their town is attacked.

**Mob raids.** Now and then at dusk raiders come for a town that's big enough (four citizens, by default). Who comes
depends on the land round the town hall: **Norse raiders** (iron helmets and axes) in taiga and snowy lands,
**marauders** (sand-coloured leathers, golden swords and bows) in deserts, badlands and savanna, and **bandits** (dark
leathers, swords and bows) or the game's **pillagers** elsewhere. Every raid but the pillagers' has a named leader,
tougher and better armoured than the rest ("Bjørn Ironside and 5 Norse raiders are attacking Lillestad from the
north!"). A citizen spots them and shouts which way they're coming from, a horn sounds, and a minute later they
arrive: more for bigger, richer towns (`maxRaiders` at most). Guards fight; everyone else shelters indoors, or runs to
the far side of town when there's nowhere to hide. Afterwards the whole town remembers it ("Hild killed four
of them"), your standing rises if you saw them through it (and drops if you weren't there), and anything broken
becomes a repair order the builders do first. `mobRaids` in the config: off, easy, normal or hard.

**Your land is protected.** Only a town's people can build, dig, or open chests and beds on its land.

**War is opt-in.** Every town starts **peaceful** and can't be attacked by players. Its founder can make it **open to
war** at the town hall (going back takes three real days, and never during a war). Two open towns can go to war:
- An officer declares it; both towns hear (and the citizens have opinions: bold sorts like it, peaceful ones and those
  with family over there don't, and that shows in your standing). After a warning period (24 real hours), one raid can
  start, inside the defenders' raid window (18:00 to 22:00 server time by default; `/stad war window`) and only while
  one of their officers is online.
- An officer leads the raid from the defenders' land; their guards are ordered to march (and may refuse a leader they
  resent). To win, hold the ground by the defenders' town hall for 60 seconds with no defender near: the banner is
  taken, a fifth of their stock (at most 512 items) is carried off, and unhappy citizens may go over to the winners.
  Defenders win by holding out for 20 minutes. During a raid attackers can break doors, fences, walls and gates and
  nothing else; beds, chests and the town hall stay locked. Killing someone who wasn't fighting is remembered on both
  sides and costs a lot of standing.
- It ends in a truce (three real days). Either side can offer peace (`/stad war peace`), or send a citizen as an
  **envoy**: they wait at the other town hall, make the case for peace in character, and an officer there can say
  "we accept" or send them home.

**Poaching.** An officer of one town can invite another town's citizen: if they're unhappy where they are, they may
come. Each town can switch this off (the town hall has a button for it and for the war stance).

`pvpRaids` in the config: off, opt_in (the default) or forced (every town can be attacked). The warning, cooldown,
truce, raid length, capture time, loot share and offline raids are all settings too.

### Big towns and hosting

Stad is tuned for towns of 50 and more. Citizens near a player (`thinkRadius`, 48 blocks) plan their day with the
local AI each morning, one or two at a time while it isn't busy and at most `plansPerDay` (16) a town; everyone else
follows their routine (their job, their needs, now and then an afternoon off), which costs nothing. Conversations
always go first. On the dev machine (Ryzen 7 9800X3D) a town of 125 working citizens costs the server about 3 ms per
tick of its 50 (`/stad perf` shows where the time goes). A town's land stays loaded while one of its members is online
(`keepLoaded`: never, while_online or always; `loadedRadius` chunks round the town hall), so builders keep building
while you're off mining. Crops only grow near players, as in vanilla. Mob raids are capped at `maxRaiders` (16).

Hosting without a big GPU:
* **A planner model.** Building designs can use a bigger model than the chat (*Use for planning* on They Will Talk's
  Models page). It only runs while someone is planning, then frees the GPU again.
* **Lent GPUs.** Players with They Will Talk on their own computer can type `/lendgpu on`: the citizens they talk to
  then think on their GPU (only their own conversations are sent). A server with no GPU at all runs on lent ones;
  players who don't lend get "can't talk right now" while nobody's lending to it (their towns still live and work).
* **Your own model server.** `externalLlmUrl` in They Will Talk's config points at Ollama, LM Studio or llama.cpp elsewhere.

### Building packs

A pack is part of a data pack: a manifest per building in `data/<namespace>/stad/buildings/<pack>/<building>.json`
naming a vanilla structure template (`data/<namespace>/structure/...nbt`, saved with a structure block, front door
facing north). See `data/stad/stad/buildings/medieval/` and `tools/packgen.py`, which generates the bundled pack.

```json
{ "type": "stad:home", "name": "Cottage", "description": "A cozy home with two beds.",
  "structure": "stad:medieval/cottage", "tags": ["small", "home"],
  "roles": [{ "block": "minecraft:oak_planks", "from": "wall", "to": "trim" }] }
```

`roles` (optional) corrects the guessed block roles, as the pack editor does.

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
| `/stad colony plan <building> [style]` | officers: plan a building in front of you (what the Building Plans do) |
| `/stad colony cancel <id>` | officers |
| `/stad citizen job <uuid> <builder\|farmer\|lumberjack\|courier\|guard\|none>` | officers (the citizen card has buttons for it) |
| `/stad draft show` | your draft building: what it is and where (and its buttons again) |
| `/stad draft approve` / `here` / `turn` / `save` / `cancel` | what the buttons under a draft do |
| `/stad draft describe <text>` | draw up a building from a description without talking (asks the local model) |
| `/stad draft spec <json>` | draw up a building from a spec (see `planner/BuildSpec.java`) |
| `/stad draft style <style>` | your draft in a style's materials |
| `/stad adopt` / `adopt as <kind> [name]` | officers: the building you're standing in becomes the town's |
| `/stad adopt check [kind]` | what it has, and what it's missing to be a home, workshop... |
| `/stad adopt give <uuid>` / `release <id>` | give the house you're in to a citizen; let a taken-over building go |
| `/stad scan pos1` / `pos2 [pos]` / `open` / `save <name>` / `clear` | the scan wand, as commands |
| `/stad pack list` / `palettes` | the buildings and styles that are loaded |
| `/stad pack edit <building>` / `delete <building>` | open a building in the pack editor; delete one of My pack's |
| `/stad pack role <building> <block> <guessed> <role>` | fix a guessed block role |
| `/stad pack export [name]` | zip My pack to share |
| `/stad build swap <id>` / `keep <id>` | answer the builder about a swapped material |
| `/stad war status` | your town's stance, wars and raids |
| `/stad war stance <peaceful\|open>` / `poaching <on\|off>` | the founder (the town hall has buttons too) |
| `/stad war window <from> <to>` | officers: the hours a player raid can start (server time; equal hours: any time) |
| `/stad war declare <town>` / `raid` / `retreat` | officers: go to war, lead the raid from their land, call it off |
| `/stad war peace <town>` / `envoy <uuid>` | officers: offer peace, or send a citizen to argue for it |
| `/stad raid start [seconds]` / `stop` | admins: raiders attack the town you're in (after a warning) |
| `/stad colony found` / `list` | admins |
| `/stad citizen talk <uuid> <text>` / `history <uuid>` | say something to a citizen nearby; what you've said to each other |
| `/stad citizen voice <citizens>` | admins: how they sound (age, pitch, pace and voice description) |
| `/stad citizen status <citizens>` | admins: what they're doing right now |
| `/stad citizen needs <citizens> <hunger> <rest>` | admins, for testing |
| `/stad perf` / `perf reset` | admins: milliseconds per tick for the citizens and the town upkeep, day plans, loaded towns |
| `/stad perf crowd <count> [job]` / `crowd clear` | admins: add test citizens (with that job, or a mix) to the town you're in; take them away again |
| `/lendgpu [on\|off]` | players with They Will Talk installed: lend your GPU to the server (They Will Talk's client command) |

## Development

Requirements: JDK 21 (Gradle downloads it if needed), and a checkout of
[They Will Talk](https://github.com/Eiriksb/they-will-talk) next to this one (0.3.0-alpha.6 or newer, for its public
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
./gradlew :mod:runClientJoin         # a dev client that joins it as "Eirik" (lendGpu in run/client/config to test lending)
./gradlew :mod:runClientJoin2        # a second one as "Tester", for multiplayer
scripts/rcon.py "list"               # talk to the dev server (enable RCON in run/server/server.properties)
```

The dev runs share TWT's AI runtime (`../they-will-talk/runtime`), so models installed for TWT work here too. The dev
server needs `online-mode=false` in `run/server/server.properties` for the offline dev clients to join.

## License

MIT
