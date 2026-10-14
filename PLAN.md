# Stad — project plan

*Working name: "Stad" (Norwegian for town). A NeoForge mod where you build a colony like MineColonies, with NPCs that
have MCA-style lives, families and relationships. Every NPC is run by a small local AI agent. You can talk to them by
typing or by voice, and you can plan buildings with them in plain language.*

Stad is an **add-on that requires [they-will-talk](https://github.com/Eiriksb/they-will-talk)** (TWT, MIT).
- **TWT provides:** talking, memory, relationships, voice, the AI runtime and model installer, and the dashboard.
- **Stad adds:** its own citizen entity, colonies, jobs, buildings and packs, the building planner, leadership, and
  raids.
- **TWT changes needed:** TWT has to expose a public API for this. See §8.

---

## 1. Pillars

1. **Talk to anyone.** Every citizen has a name, a personality, a voice, a job, a home, a family and memories. They
   answer in character, by text or voice, and they remember you, each other and what happened in the town.
2. **Build a town.** Place a town hall, recruit citizens, give them homes and jobs (builder, farmer, lumberjack,
   miner, courier, guard, cook...). They work for real: gathering, hauling, crafting and building block by block.
3. **Any house can work.** Buildings come from **packs** (bundled styles, or packs you make yourself). Any blueprint,
   or a house you built by hand, can become a home or a workplace if it has the right furniture. The block palette
   can be restyled to suit the biome, the style or the materials on hand.
4. **Plan by talking.** "Build me a small stone cottage with two bedrooms and a chimney, facing the lake." The builder
   writes a plan, shows you a ghost preview and changes it as you talk ("make it taller", "use spruce"), then builds
   it.
5. **Local and private.** All AI runs on your own machine: the server's GPU, or optionally a player's own GPU. No
   cloud, no API keys.

## 2. Key decisions (with recommendations)

| Decision | Recommendation | Why |
|---|---|---|
| Platform | **NeoForge 1.21.1, Java 21** | Same as TWT, so its code ports directly. MCA and MineColonies are there too for reference. |
| Single- vs multiplayer | **Both, from day one.** Everything runs on the server and the client only displays it. Singleplayer is just the integrated server | Adding multiplayer later means rewriting every GUI and preview. Building it in from M1 costs little. |
| Obedience | **Citizens can refuse orders from a bad leader** (see §6.6). Game code decides whether they're willing; the LLM only puts the answer into words | This makes how you lead matter. Keeping the decision in code makes it fair, explainable and impossible to talk around. |
| Relationship to TWT | **TWT is a required dependency.** Stad registers its citizens with TWT as a new kind of villager and extends conversations, prompts, memory and the dashboard through a public TWT API (§8) | No copied code. Fixes and features in TWT reach Stad automatically. Generic improvements (better memory, villager↔villager relations, a planner model) also help MCA, MineColonies and vanilla villagers. |
| Dependence on MineColonies / MCA | **None.** Reimplement the ideas, not the code | Both are GPL-3.0, and their entities and data models would fight ours. Don't copy their code or blueprints. |
| "An AI agent per NPC" | **One logical agent per NPC, all sharing one llama-server with several slots** (see §4) | 30+ separate model processes won't fit in 16 GB of VRAM. A shared server with per-agent prompt, memory and goals behaves the same from the player's side. |
| What the LLM controls | **Decisions and dialogue, never ticks.** It calls tools; Java checks every call and does the work | Small models are slow and sometimes wrong. Pathfinding, building and job loops have to be deterministic. |
| How the LLM designs buildings | **The LLM writes a structured `BuildSpec`; a Java generator turns it into blocks** | A 2–4B model can't write voxels reliably, but it can fill in a JSON schema well (llama.cpp grammar-constrained output). |
| Building format | **Vanilla structure `.nbt` files plus a small JSON manifest** | Players already know structure blocks. No Structurize dependency. Packs are easy to make and share. |

## 3. Architecture

```
 ┌────────────────────────────── Minecraft server (NeoForge) ──────────────────────────────────┐
 │ STAD                                                                                         │
 │  Citizen entity ──► Behaviour layer (Java, every tick)                                       │
 │   (genes/skin,        schedule · needs · pathing · Job state machines · build placement     │
 │    inventory)               ▲ intents / goals                │ events (perception)           │
 │                             │                                ▼                               │
 │                     Agent layer (plan day · react · consolidate · tool calls, schema-checked)│
 │  Colony ─ town hall · claim · ranks · housing · jobs · requests · warehouse · standing       │
 │  Buildings ─ Pack registry · Blueprint · Palette restyle · BuildOrders · House adoption      │
 │  Planner ─ BuildSpec (LLM) ─► Generator (Java) ─► Blueprint ─► ghost preview ─► BuildOrder   │
 │  Raids ─ mob raids · war stance · PvP raids · snapshots/repair       stad.db (world/stad/)   │
 │                                                                                              │
 │  twt/ bridge: StadAdapter · PromptContributors · conversation hooks · reflection fields      │
 │ ═════════════════════════════ TWT public API (dev.eiriksb.theywilltalk.api) ════════════════ │
 │ THEY WILL TALK                                                                               │
 │  Conversation ─ chat / voice · targeting · group turn-taking · streaming TTS · subtitles     │
 │  Memory ─ memories · relationships · villager↔villager relations · gossip · reflection       │
 │  LlmClient ─ priority queue · chat model + planner model · inference backends                │
 │  Runtime ─ installs and supervises llama-server + TTS        theywilltalk.db (world/)        │
 │  Dashboard :8765 ─ villagers, memories, family tree, map + Stad's pages (colony, builds)     │
 └──────────────────────────────────────────────────────────────────────────────────────────────┘
        ▲ packets: chat, plan previews, GUIs                ▲ optional: inference jobs
 ┌──────┴──────────── Client ─────────────┐        ┌──────┴── "Lend my GPU" client ──┐
 │ chat screen · town hall GUI · ghost     │        │ runs its own llama-server for    │
 │ renderer · pack editor · scan wand      │        │ jobs the server sends it         │
 └─────────────────────────────────────────┘        └──────────────────────────────────┘
```

In singleplayer the integrated server runs inside the client, so "server-hosted" already means "on my PC".

## 4. The agent model

Each NPC is a **logical agent**: a persona, memories, relationships, the current day plan and a set of tools. All
agents share one llama.cpp server (TWT already runs `-np <slots>` with a priority queue in `LlmClient`). The design
follows the *Generative Agents* loop (Park et al., 2023), cut down for small models:

| Loop | When | LLM call | Output |
|---|---|---|---|
| **Converse** | When a player or NPC speaks to it | streamed reply plus an emotion tag (as in TWT) | speech, and optional tool calls |
| **Reflect** | After each conversation (TWT's `reflect()`) | schema-constrained | affinity delta, mood, one memory |
| **Plan day** | Morning, for active citizens | schema-constrained | 2–4 intentions ("finish the bakery roof", "visit Ingrid") that guide the behaviour layer |
| **Consolidate** | Evening, or when memory gets large | schema-constrained | day summary, importance scores, old memories folded into summaries |
| **React** | Important events (raid, death, a new building, missing materials) | short call, tools allowed | intent, or a line to say |

**Think budget / level of detail:** Citizens near a player get the full set of loops. Distant citizens only run the
behaviour layer and get one summarised "what happened today" reflection. A town of 30 citizens doing a plan and a
reflection every Minecraft day is roughly 10–20k tokens per 20 real minutes. That's small next to conversations,
which always go first (`Priority.CONVERSATION`).

**Tools** (sent as a JSON-schema union; every call is checked against game state on the server thread):

- `go_to(place)`, `follow(player)`, `stop()`
- `set_goal(text, priority)`: adds an intention the behaviour layer can act on (only known goal types count)
- `request_items(item, count, reason)`: goes into the colony request system
- `give_item(target, item, count)`, `accept_errand(...)` (TWT's errands)
- `propose_build(spec)` and `revise_build(patch)`: builder only (§6.4)
- `assign_home(building)` and `take_job(building)`: only with the player's permission
- `remember(text, importance)`, `tell(npc, text)`: gossip on purpose

**Hosting backends** (one `InferenceBackend` interface, added to TWT's `LlmClient` so every TWT villager can use it):

1. *Local server* (default): the server's own llama-server, installed by TWT's Models page.
2. *Client-lent GPU* (later, multiplayer): a player opts in, their client runs llama-server, and the server sends it
   jobs over a mod packet channel. **Only jobs about that player** are sent (their own conversations with nearby
   NPCs), because prompts contain memories about other players. The server still checks every result, so a modified
   client can only make an NPC say odd things. It can't change game state.
3. *External OpenAI-compatible endpoint* (Ollama, LM Studio): TWT already supports this.

**Models:** start with TWT's catalogue (Gemma 4 E2B/E4B). Add a separate **planner model** setting in TWT so building plans
can use a larger model while chat stays on the small, fast one.

## 5. Memory and social network

TWT already stores `villagers`, `relationships` (villager↔player), `memories` (villager, player, kind, text),
`conversations`, `messages`, `family`, `events` and `errands`, and does gossip with a village-wide query. **Stad
citizens live in those same tables**, with `kind = 'stad'`. The data is split between the two mods:

**Added to TWT's own database** (useful for every kind of villager, so it belongs in TWT):

```sql
-- memories: about anyone, not just players; ranked and summarisable
ALTER memories ADD subject_type TEXT,        -- player | villager | building | place | colony
                   subject_id   TEXT,
                   importance   INTEGER,     -- 1..10, set by reflect/consolidate
                   source       TEXT,        -- witnessed | told_by:<uuid> | reflection | summary
                   summarised_into INTEGER;  -- id of the summary row that replaced it
CREATE VIRTUAL TABLE memories_fts USING fts5(text, content='memories');   -- keyword recall, no embeddings needed

CREATE TABLE villager_relations (a TEXT, b TEXT, affinity INTEGER, familiarity INTEGER,
                                 kind TEXT,  -- friend | rival | partner | spouse | parent ...
                                 last_interaction INTEGER, PRIMARY KEY (a, b));
```

**Stad's own data.** Since M2, colony state (colonies, ranks, citizens' homes, the order log) is saved with the world
as Minecraft saved data (`data/stad_colonies.dat`), which keeps it in step with the world and its backups. Needs and
looks are saved on the citizen entity. The tables below show the shape of the data. Append-heavy history (standing
events, raids) may move to a SQLite file (`world/stad/stad.db`) when it gets large:

```sql
CREATE TABLE citizens (uuid TEXT PRIMARY KEY, colony, genes_json, job, home, workplace, skills_json, happiness);
CREATE TABLE plans    (citizen TEXT, day INTEGER, idx INTEGER, text TEXT, goal_type TEXT, status TEXT);
CREATE TABLE colonies (id, name, owner, dimension, x, y, z, radius, style_pack, difficulty, created,
                       war_stance TEXT DEFAULT 'peaceful', stance_changed INTEGER, poaching INTEGER DEFAULT 1);
CREATE TABLE colony_members  (colony, player, rank);                  -- owner | officer | member | visitor
CREATE TABLE standing        (colony, player, score, updated);        -- leadership standing for each player
CREATE TABLE standing_events (id, colony, player, ts, delta, cause, citizen);  -- powers "why" explanations
CREATE TABLE orders          (id, colony, player, citizen, kind, args_json, outcome, reason, ts);  -- accepted | grudging | refused
CREATE TABLE colony_relations(a, b, state, since, truce_until);       -- peace | war_declared | at_war | truce | allied
CREATE TABLE raids           (id, colony, attacker_kind, attacker, started, ended, outcome, loot_json, snapshot_id);
CREATE TABLE citizen_moves   (citizen, from_colony, to_colony, ts, reason);  -- poaching, defection, following family
CREATE TABLE buildings(id, colony, type, pack, blueprint, level, x, y, z, rotation, mirror,
                       origin TEXT,  -- pack | generated | adopted
                       owner_citizen, workers_json);
CREATE TABLE build_orders (id, colony, building, kind, status, builder, progress, materials_json, spec_json);
```

**Retrieval** for a prompt: recent memories about this subject, then the top memories by
`importance × recency_decay` matched with FTS on words from the current utterance, plus a few gossip items. Embeddings
(llama-server `--embedding`) are an optional later step if keyword recall turns out too weak.

**Gossip that spreads:** when two citizens chat (TWT's ambient chatter), each may pass on memories with
`importance ≥ n` about a shared subject. The copy is stored as `source=told_by:<uuid>` with lower importance and can
get distorted a little. News spreads along friendships instead of reaching the whole village at once.

**MCA-style life:** genes/skin (body, hair and clothing layers), age (child, teen, adult), courting → marriage →
children, inheriting traits and persona tendencies, and needs (food, sleep, social, comfort) that feed mood. These
live in the behaviour layer. The agent only sees them as facts in the prompt.

## 6. Colony, jobs and buildings

### 6.1 Colony core
- **Town hall** block: founds a colony, sets the claim radius, opens the colony GUI (citizens, buildings, requests,
  build queue, happiness).
- **Recruitment:** new citizens arrive over time, depending on housing and happiness. Players can also recruit
  wandering NPCs by talking to them. The agent decides whether it's persuaded.
- **Schedule:** sleep, work, eat, socialise, with interruptions for raids and weather.
- **Requests and logistics:** citizens post requests (tools, food, building materials). They're filled from the
  warehouse by couriers, or by the player. Kept simpler than MineColonies: one warehouse, priority queue, no complex
  crafting chains in v1.

### 6.2 Jobs
`Job` = a workplace building type, required blocks, a Java state machine and skills that improve with use. The LLM
adds talk and priorities on top, nothing else.

| Phase | Jobs |
|---|---|
| v1 | **Builder**, Courier (warehouse), Farmer, Lumberjack |
| v2 | Miner, Guard, Cook, Fisher, Shepherd (done: guard in M8, the rest in M11) |
| later | Smith, Teacher (school), Healer, Merchant: M14 (§6.9) |

### 6.3 Packs and blueprints

```
<pack>/                                   # a data pack (zip or folder): a world's datapacks, or stadpacks/ for every world
  pack.json        { "id", "name", "author", "description", "icon", "biomes": [...], "palette": "palettes/default.json" }
  palettes/*.json  { "wall": "minecraft:spruce_planks", "roof": "minecraft:dark_oak_stairs", "trim": ..., "window": ..., ... }
  buildings/<id>/
    building.json  { "type": "stad:home|stad:builder_hut|...", "levels": [{ "structure": "l1.nbt" }, ...],
                     "tags": ["small", "cozy"], "footprint": [w, d], "rotatable": true }
    l1.nbt, l2.nbt ...                    # vanilla structure templates
```

- **Functional blocks** (M3) are found by block type: beds are homes, chests and barrels storage. *Markers* (below)
  come with custom packs in M7.
- **Functional markers** use vanilla structure blocks in *Data* mode (the same thing vanilla uses for loot chests):
  `stad:bed`, `stad:work`, `stad:storage`, `stad:door`, `stad:sit`. They tell citizens where to sleep, work and store
  things.
- **Building types declare requirements, not layouts.** A builder's hut needs a crafting table, storage and a bed slot.
  Any blueprint that has them qualifies. That's what lets any house be adapted.
- **Semantic block roles.** On import, every block gets a role (wall, roof, floor, trim, pillar, window, door, light,
  decor) from tags and position (e.g. stairs and slabs above the top wall row become roof). The pack editor lets you
  fix wrong guesses. A **palette** maps roles to blocks, so the same house can be built in Nordic spruce, desert
  sandstone or whatever is in the warehouse.
- **Material substitution in conversation:** "We're out of spruce. Want me to use birch for the walls?" A mismatch
  between palette and stock becomes a question the builder asks you.

### 6.4 Talk-to-plan (the builder as architect)

```
"Small stone cottage, two bedrooms,      ┌──────────────┐   BuildSpec (JSON, schema-constrained)
 chimney, facing the lake"  ───────────► │ Builder agent│ ─► { style, footprint:[9,7], floors:1,
                                         └──────────────┘      roof:"gable", rooms:[bedroom×2, kitchen],
                                                                materials:{wall:"cobblestone", ...},
                                                                features:["chimney"], facing:"lake" }
                                                                         │
           ghost preview + material list ◄── Blueprint ◄── Generator (Java, deterministic)
                     │                                   footprint → rooms (BSP split) → walls/floors →
   "make it taller"  │  revise_build(patch)              roof (gable/hip/flat/shed) → openings →
                     ▼                                   furniture per room type → markers → palette
               approve ─► BuildOrder ─► builder gathers/requests materials ─► builds
```

- The draft spec stays in the conversation. Each revision gives the LLM the current JSON plus the request and gets a
  full new spec back, which avoids fragile patch formats.
- **Two modes:** *generate* (procedural, from the spec) and *pick & adapt* (the LLM picks the closest pack blueprint by
  tags/type/size, then palette and level changes are applied). The second is more reliable on small models and should
  ship first.
- Approved generated buildings can be **saved into "My pack"** and reused.
- Placement: you point at a spot or say "next to the bakery". Named places resolve through the colony's building
  list. The ghost preview shows where it goes.

### 6.5 Making your own packs and adopting houses
- **Scan wand:** select two corners → save as a blueprint in your pack (roles and markers detected automatically,
  then editable in a GUI).
- **Adopt a house:** point at a building you made, or tell a citizen "this is your home now". Flood-fill the inside
  (with a volume limit), find beds, doors, workstations and containers, and check it has a roof. It becomes a home or
  workplace if it meets that building type's requirements. Otherwise the citizen tells you what's missing ("I'd need a
  bed and a furnace in here"). Adopted buildings can be upgraded: the builder plans an extension with the generator.

### 6.6 Leadership: citizens can refuse a bad leader

Every order goes through the same check, whether it comes from chat ("go chop wood"), the GUI (assign job or home)
or a build approval.

**Willingness** is a number computed in Java for each citizen and each player:

```
willingness = standing(player, colony)        -- how the town sees you as a leader
            + affinity(citizen, player)        -- personal relationship (TWT's relationships table)
            + happiness(citizen)               -- food, housing, safety, workload, social
            + persona modifier                 -- grumpy/paranoid -30%, friendly/peaceful +, etc.
            - order cost                       -- dangerous, unpleasant, outside their skills, at night, unpaid
```

**Standing** is a town-wide score for each player. It's built from what the town has seen and gossiped about:

| Raises standing | Lowers standing |
|---|---|
| Filling requests, feeding the town, good housing | Unfilled requests, hunger, homeless citizens |
| Defending in raids, healing, gifts | Hitting or killing citizens, citizens dying while you watched |
| Keeping promises made in conversation | Broken promises (recorded by reflection as `promise` memories) |
| Fair workloads and rest days | Overwork, ordering people into danger, demolishing someone's home |

Because standing comes from memories and gossip, a bad deed spreads through the town over time. The town doesn't
learn about it all at once.

**Result of an order:**

| Willingness | What happens |
|---|---|
| High | Does it gladly, sometimes adds ideas |
| Medium | Does it but complains, works a bit slower |
| Low | **Refuses or negotiates:** "Not until you fix the roof on my house." This becomes a request you can fill. |
| Very low (town-wide) | **Strike** (work stops in a building), or citizens **leave the colony**, sometimes for a rival player's colony (see *Poaching* below). |

- **Explainable:** the citizen gives the main reason, taken from the biggest negative factor ("You let Ingrid starve
  last winter"). The colony GUI shows your standing and its top causes.
- **Can't be talked around:** the LLM gets the outcome (accept, grudging or refuse, plus the reason) as a fact in the
  prompt. Clever wording from the player can't flip it. Apologies, gifts and kept promises still raise affinity and
  standing over time through reflection.
- **Safety limits:** guards always defend against raids, citizens always flee danger, and nobody refuses to eat or
  sleep. Refusal only covers orders.
- **Difficulty setting:** *Loyal* (never refuses, only complains), *Normal*, *Harsh*. Servers can pick.

**Multiplayer:** standing is tracked for each player. Colonies have ranks (Owner, Officer, Member, Visitor), and rank
limits which orders a player may give at all. Willingness then decides whether the citizen actually goes along. So an
officer the town hates gets refused even if they have permission.

**Poaching (on by default):** unhappy citizens can be talked into joining a rival's colony.
- A rival can only recruit a citizen whose willingness toward their own leader is low, and only by talking to them in
  person. The citizen's agent weighs what's offered (a better house, food, a job they like, a friend who already moved)
  against their ties at home: family, a spouse, a home they love.
- Before leaving, the citizen often says so: "Bjørn's town feeds its people. Maybe I should go there." The words
  come from the LLM. Whether they actually leave is decided in code, same as for orders.
- A citizen who leaves takes their memories with them. In their new town they'll tell stories about how you treated
  them, and that gossip lowers your standing with anyone who hears it.
- Family pulls both ways. A citizen may leave to follow a spouse, and children go with their parents.
- Server config: `poaching = on | same_team_only | off`.

### 6.7 Raids and rivalry

**Mob raids (PvE).** These scale with colony size and wealth, as in MineColonies.
- Raider factions that fit the biome: pillagers, plus custom bandits, Norse raiders and desert marauders. Raids
  announce themselves first (a horn, scouts, a citizen running in to warn you), so citizens get time to react.
- **Guards** (Guard job, plus a barracks and watchtowers) patrol, man walls and fight. Other citizens shelter at home
  or in a designated shelter. Brave personalities may pick up a weapon on their own.
- The raid becomes memories for everyone who saw it: "The bandits burned the bakery and Leif saved my children."
  Defending well raises your standing. Being away or unprepared lowers it.
- **Repairs:** before the fight, the blocks in the claimed area are snapshotted. Afterwards the builder gets a repair
  order (with materials), so damage gets fixed without being lost forever. Citizens talk about the rebuild.

**Player raids (PvP).** One colony attacks another. **These are opt-in for each colony.** Players who want war can
have it, and players who don't are never raided.

- **War stance** for each colony, set by the owner at the town hall:
  - *Peaceful* (the default): can't be raided by players and can't declare war on anyone.
  - *Open to war*: can declare war on, and be raided by, other colonies that are also *Open to war*.
  - War only happens when **both** sides are open. A peaceful colony is never forced into it.
- **No dodging:** switching to *Open* takes effect right away. Switching back to *Peaceful* takes a cooldown (default
  3 real days) and can't happen while a war is declared or under way. You can't go open for one raid and then hide.
- **Poaching gets its own switch** for each colony (default on), so a peaceful builder can still keep it on for fun or
  turn it off.
- **The stance is visible** on the town hall banner, the colony GUI, the dashboard and the BlueMap marker. Citizens
  have opinions about it too: peaceful types like a peaceful stance, and boastful or brave ones want glory.
- **Server config:** `pvp_raids = off | opt_in | forced`. The default is `opt_in`, and every new colony starts
  Peaceful. `forced` is for hardcore servers. Mob raids are separate and have their own difficulty.

The rules below only apply between colonies that are both open to war. They exist so it's fun, not griefing:

| Rule | Design |
|---|---|
| Declaring war | The owner or an officer declares war at the town hall. There's a warning period (default 24 real hours) and both towns' citizens hear about it and talk about it. |
| Raid windows | Raids can only start inside the defender's chosen window (e.g. evenings), and only while a defender with the right rank is online, or with offline protection otherwise (server config). |
| Objective | Capture the town hall banner (stand near it for N seconds while defenders are cleared) or reach a loot target. The raid ends on capture, a time limit or a retreat. |
| What's at stake | A capped share of warehouse stock, plus a chance for unhappy citizens to defect to the winner (ties into poaching). No permanent loss of buildings, which are repaired from the snapshot. |
| Protection | Outside raids, blocks inside a claim are protected from other players. During a raid only certain blocks can break (doors, fences, walls and gates). Beds, the town hall and storage are locked. |
| Afterwards | A truce timer (no new war for N days). Peace, tribute or alliances can be agreed at the town halls, and either side's citizens can be sent as **envoys** who negotiate in character. |

**Citizens have opinions about war too**, and this ties into leadership:
- Declaring an unprovoked war on a town your citizens trade with or have family in costs you standing. Defending your
  town raises it.
- Guards ordered to attack go through the willingness check, so a bad leader's army may refuse to march.
- Harming the other town's civilians (not guards) costs a lot of standing at home. It also makes citizens of both
  towns remember you.
- Captured or defecting citizens remember the war, so a town you raided will have grudges for generations
  (consolidated memories are passed down to children).

### 6.8 The architect's table and workplaces

Talking to the builder (§6.4) suits "a small cottage". The **architect's table** is for when you know exactly what you
want, on exactly this piece of land: mark out a site, look at it in 3D, have the table draw a building that fits, then
change it block by block. It works for any building type, and with **workplaces** (below) every job gets a building
type of its own.

**Marking the site.** The surveyor's tool is the scan wand (§6.5), which already marks two corners. Right-click the
table with a marked wand to load the site, or type the corners into the table's screen (filled in from the wand). The
site must be on the town's land, at most 32 × 32 blocks and 32 high. It goes from the lower corner's height up to the
higher corner's, and at least 12 up so there's room for a roof.

**The 3D view.** The server copies the site's blocks, plus four blocks of the land round it, and sends them to the
client in one palette-compressed payload (about 40k blocks at most). The screen draws them with the game's own block
models, from a camera you drag to turn, scroll to zoom and middle-drag to pan. The land round the site is drawn faded
and can't be edited. Each layer of blocks is meshed into its own vertex buffers, so an edit only re-meshes its layer
and the two next to it. A **layer slider** cuts the view off above a chosen layer so you can work inside, and
there's a ground grid and a north arrow.

```
┌───────────────────────────────────────────────────────────────┬──────────────────────┐
│                                                               │ Fishery  ▾  (type)   │
│                    3D view of the site                        │ Style: Fjord ▾       │
│          drag: turn · scroll: zoom · middle-drag: pan         │ Floors 1  Roof gable │
│                                                               │ [Generate] [Describe]│
│                                                               ├──────────────────────┤
│                                                     layer ▲   │ ✓ walls all round    │
│                                                      [ 7 ]    │ ✓ a roof             │
│                                                       ▼       │ ✗ a barrel           │
│                                                               │ ✓ by water           │
├───────────────────────────────────────────────────────────────┼──────────────────────┤
│ [place][break][pick][box][wall][floor][line][replace][mirror] │ 212 blocks, 38 short │
│ blocks: [spruce planks][spruce log][stone bricks]... [search] │ [Save to My pack]    │
│ ↶ undo  ↷ redo                                                │ [Plan it here]       │
└───────────────────────────────────────────────────────────────┴──────────────────────┘
```

**Designing: a generated start, then hand edits.**
- **Generate:** pick the building type, a style (any of the seven palettes), floors, roof, rooms and features. The
  generator (§6.4) fills the site with a building that fits it. Its size limit goes up from 17 to 32, and it stands on
  the site's ground (`Survey`). **Start from a pack building** works too, turned to fit the site when it fits.
- **Describe:** type a change ("put the door on the lake side, add a second floor") and the planner model revises the
  spec, as in §6.4.
- **Hand edits:** place, break, pick (middle-click), box, hollow walls, floor, line, replace, a mirror axis, copy and
  paste, and undo and redo (100 steps). The block list is a searchable list of every block a survival player could
  place (no command blocks, barriers or bedrock), with how many of each the town has in store. The style's role
  blocks (wall, roof, floor, trim) come first. R turns the block in hand. Stairs, doors, slabs and logs face the way
  the click and the camera suggest. Fences, panes and stairs join up through `updateShape` on the design's own blocks,
  never the real world.
- **Hand edits are kept as a layer over the generated building.** The design is the generated spec plus a list of
  edits. Change the roof or the floors and the building is generated again with the edits put back on top, so a hand
  edit survives a change of roof. "Flatten" turns the whole thing into plain blocks, after which the spec no longer
  applies.
- **Markers in the editor** (M7's leftover): bed, work spot, storage and door markers are placed as overlay icons
  rather than structure blocks. They're saved like the pack format's markers.

**What it's for.** The type list holds every building type, with the new workplaces among them. The checklist on the
right runs `Requirements` on the design (via `Contents.of(blueprint)`) after every edit. Requirements that
depend on where the building stands, like "by water" or "next to farmland", are checked against the real land round
the site. You can save a design that doesn't meet its requirements yet; you can't plan it until it does.

**Saving and building.**
- **Save to My pack:** name it, tag it, and it can be reused anywhere (with the Building Plans, with the builder, and
  at the table).
- **Plan it here:** it becomes a planned building on the site and goes through the same order checks as any other
  (rank, standing, §6.6). Blocks already on the site that the design leaves out become clearing steps, which
  Construction already has. The material list shows what's in store and what's short, and the town asks for whatever
  is short, as it does now.
- You can redesign a building that's already there: load its site and the existing blocks are where you start.

**Free build (M13).** **Build in it** at the table takes you to the drafting world (`stad:drafting`, a void
dimension, always lit full bright like the table's view and without weather) in creative: flying, any block, breaking at a touch. You build in a copy of the site and
the land round it, with the design already in it, as the table's view shows it; the real world is never touched. When
you're done, what you built becomes the table's design (on top of the generated building, like hand edits), and your
own inventory, game mode and place come back. The builders then build it with real materials.
- The copy gets the real place's biomes, spot by spot, so grass, leaves and water match. The site is drawn as a faint
  grid with a yellow edge, the space you can build in.
- Each player gets their own stretch of the drafting world (1024 blocks apart), in an invisible box of barriers with
  room to stand back from the site; barriers also hold in water and lava at the edge of the copy.
- Your inventory, game mode and position are saved to the world's data before you go. The session is saved too, and
  the drafting world keeps the copy, so logging out or a restart carries on where you were.
- Only the site can be changed; the land round it is there to look at. You can only place blocks a design allows.
- Nothing lives in the drafting world: items, orbs and mobs never appear (sand and gravel still fall). You can't open
  containers or ender chests, sleep, use items that aren't blocks (buckets, spawn eggs, flint and steel...), or touch
  entities, and explosions break nothing.
- **B** opens Keep it / Throw away / Keep building; Done and Cancel are also buttons in chat (and
  `/stad build done|cancel`). Keeping it opens the table again to show the result.

**The table itself.**
- How it's made: a cartography table with Building Plans on top.
- It belongs to the town whose land it's on. Officers and owners can edit; members can look.
- It keeps its project (site, spec, edits, markers) in its block entity, compressed. Breaking the table keeps the
  project on the item.
- One person edits at a time ("Astrid is using the table"); others watch read-only.
- Each edit goes to the server as a small change. The server checks the player's rank, that the change is inside the
  site, and that the block is allowed, then applies it to the project. The client applies it straight away and rolls
  back if the server says no.

**Workplaces (a real building for every job).**

New building types join the ones that already exist (builder's hut, warehouse, kitchen, mine, barracks, watchtower).
They take their requirements from vanilla's job blocks:

| Job | Workplace | Needs (besides walls, roof and a door) |
|---|---|---|
| Farmer | Farmhouse | a composter and a chest; farmland within 16 blocks |
| Lumberjack | Lumber camp | a chest and a crafting table; trees within 24 blocks |
| Fisher | Fishery | a barrel; water (8+ blocks) within 6 blocks |
| Shepherd | Barn | a loom and a hay bale; a fenced pen inside or next to it |
| Cook | Kitchen | (as now) somewhere to cook |
| Miner | Mine | (as now) the shaft |
| Builder | Builder's hut | (as now) |
| Courier | Warehouse | (as now) |
| Guard | Barracks or watchtower | (as now) |

- **Slots.** A workplace takes one worker per work-spot marker, or per job block when it has no markers, capped per
  type. The town hall screen lists the workplaces and who works where.
- **Assignment.** Someone given a job is put in the nearest workplace of that kind with a free slot. Players can move
  them ("work at the lakeside fishery") and the citizen's chat screen shows it. Building a workplace gives its slots
  to jobless or unplaced workers of that job.
- **What it changes:**
  - The worker's day starts and ends at the workplace (the DayPlan's work block).
  - Their haul goes into the workplace's chests first. Couriers carry it on from there (`WorkGoal.deliverTo`).
  - Their tools are kept there.
  - The job's work area centres on the workplace: farmers tend the farmland round their farmhouse, fishers fish near
    their fishery, lumberjacks fell the trees round their camp. In big towns this spreads workers out instead of all
    of them heading for the same field.
  - A worker with a workplace works faster (a quarter faster) and is happier. One without still works, as now, but
    grumbles about it (a small standing cost when it lasts). Existing towns keep working.
  - Prompt facts: "you work at the Lakeside Fishery". Citizens can talk about their workplace and ask for one.

### 6.9 The later jobs (M14)

Four jobs §6.2 left for later, each with a workplace of its own (§6.8), plus two things they need: **skills**, so
work gets better with practice, and **real tools**, so a smith has something to do.

**Skills.** Every citizen has a skill in each job they've done, from the work they do (a crop harvested, a log
chopped, a block placed). Ten levels, from novice to master; each level makes the work 2% quicker (on top of the
workplace's quarter). The level shows in the chat screen and goes into the prompt ("you're a journeyman miner"), and
changing job starts the new one from its own level, so old skills aren't lost.

**Real tools.** Farmers need a hoe, lumberjacks an axe, miners a pickaxe, fishers a rod and shepherds shears (guards
already take a real sword). A worker takes the best one from their workplace's chests, then the town's stores, uses
it until it breaks, and at the end of the day puts it back in their workplace (the "tools kept at the workplace" left
from M13). Without one they make do by hand, a third slower, and the town asks for one.

**Smith** (smithy: an anvil, a furnace or blast furnace, a chest). In order:
- **mends** worn tools from the stores at the anvil, with the tool's own repair material (an iron ingot for iron
  tools, cobblestone for stone...), a quarter of its wear per unit;
- **makes** tools the town's workers are short of (and swords for unarmed guards), from the game's own crafting
  recipes, with what's in the stores (sticks from planks, planks from logs if it has to); the best tier it can;
- **smelts** raw ore the miner brings up into ingots at the furnace.

**Healer** (infirmary: a brewing stand, a bed, a chest). Citizens can now **fall ill**: a small chance each morning,
more for the hungry and the homeless. Ill, they don't work, are less happy, and stay in bed (in the infirmary if
there's a free bed, else at home); it passes by itself in about three days. The healer walks round to the ill and the
wounded: two treatments cure an illness, and each treatment mends a wounded citizen by a few hearts.

**Merchant** (market: a bell, a chest). The town's money is **emeralds** in its stores. The merchant stands at the
market by day, and players trade with them (Trade in the chat screen, or sneak and right-click), in the game's own
trading screen:
- the town **sells** what it has more of than it keeps for itself (food over what the town hall needs, logs, stone,
  ingots...), for emeralds;
- the town **buys** what it's asked for (builders' materials, tools, swords, food when short) with its emeralds.
Every trade moves the goods in and out of the town's real chests. Prices come from a table of what things are worth
(with a fallback by rarity), a little better as the merchant gets more skilled.

**Teacher** (school: a lectern, a light). The teacher stands at the lectern by day; the town's children go to school
while the teacher is there. Each day at school adds to a child's schooling (at most ten); when they grow up, schooling
makes them learn every job faster (up to twice as fast).

All four get bundled designs in each pack (smithy, infirmary, market, school: twelve in all), can be planned at the
architect's table (which furnishes the anvil, brewing stand, bell or lectern if a generated one lacks it), and are
assigned and moved like the other workplaces. Game tests for skills, tools wearing out and coming back, each job's
work, illness, and trades moving real goods.

### 6.10 Playable without knowing the code

A town should get going with what a new player would do, and say what it needs when it can't.

**Why, everywhere.** Each job's goal tells its citizen what they're doing or what's stopping them, with the fix ("No
seeds to sow: put wheat seeds in the town hall"); needs (asleep, ill, hungry with no food, on strike, no job) come
first. It's on the citizen's card, in the town hall and in their prompt (so they can say it), and a problem that lasts a
minute is told to the leaders once a day. The town hall has tabs: the next steps in getting a town going (beds, a
builder, a house, its materials, food, workplaces, a warehouse and courier, a guard), each citizen's status, every
job's needs and whether the town has them, and the buildings. The card's job buttons explain themselves on hover and
are greyed out where the town lacks what the job needs. The building plans are grouped by what buildings are for.

**Jobs that set themselves up.** A farmer with no field marks one out (9 by 9, flat grass or dirt by their farmhouse or
the town hall), levels and tills it, digs the water hole and sows it, with seeds from the stores or picked from the
grass. A field can also be planned with the building plans, like a building, but the farmers make it, not the
builders. Builders build ahead of a missing material (anything that doesn't hang on other blocks or fall), make up the
ground under a building, fetch from warehouses, and refuse open water. Lumberjacks plant a grove from saplings when
there are no trees; shepherds lead in animals from just outside town; cooks also cook what's in the town hall. A
citizen with no job takes a first one more willingly (they came looking for work).

### 6.11 Land at the architect's table, and Stad's own look

**Land as well as buildings.** The table's kinds include **field**, **pen** and **grove**. For these, Lay out ▾
replaces the building options with land options (a `Yard`, kept with the project like a building's spec, so changing
one redraws it with the hand edits on top):
- **Field**: levelled at the site's ground (dirt filled in below, everything cleared above), farmland round water
  channels along the long side (or a hole in each 9 by 9), as few as keep every plot within four blocks of water, spread
  evenly; the crop on top (one, or mixed strips); a fence with a gate on the door's side, a path round the inside and
  lamps on the corner posts if asked for. Needs: farmland, and no dry plot.
- **Pen**: levelled, a fence with a gate, and for 8 by 8 or bigger a shelter (slab roof on posts over a hay bale) and a
  cauldron of water, two blocks from the fence so nothing jumps out. Needs: a fence that shuts in some ground (checked
  level by level: an enclosed patch an animal can stand on, next to the design's fence) and a gate.
- **Grove**: saplings on a grid 3 to 6 apart, on the land as it is (dirt put under where it isn't), optionally a path
  through the middle. Needs: four saplings; nicer with two blocks between them.

**The land is for the workers.** Planning one turns its farmland into `stad:plot` markers (`stad:plot_carrots` when the
design has a crop on it), its water into `stad:water`, its saplings into `stad:sapling_<tree>` and the middle of a pen
into `stad:pen_<animals>`; markers that are land don't make the builder clear their spot. The builders build the rest
(fence, path, shelter, the dirt that levels it); a field or grove with nothing else is done at once. The farmer digs
every water marker, tills every plot and sows each with its crop, fetching it from the stores first; the lumberjack
plants grove spots first (with the sapling asked for), and fells the grown trees (a grove's trees aren't "inside a
building"); the shepherd leads loose animals of a pen's kind in through its gate (opening and closing it), and a pair
from outside when the pen has fewer than two. The 9 by 9 field of §6.10 is the same thing with only markers. Water is
allowed in designs now (it has no item). Ground blocks a survival player can't pick up (grass, path, farmland) cost
dirt.

**Land for the other jobs.** The same `Yard` also draws a **quarry** (its pit's corners marked
`stad:quarry_<depth>`, a fence and lamps round it; `Mine` digs any rectangular `Pit` the way it digs a mine's 5x5 shaft,
steps spiralling down its rim), a **dock** (a 3-wide pier at the water's level from the door's side, on log posts,
`stad:fish` spots along it; fishers use a finished dock's spots before the shore), a **guard post** (a platform four up
on logs, a ladder, a railing, a `stad:post` watch spot) and a **wall** (on the land as it is, with a door; `stad:post`
on the corners). Guards patrol to watch spots (twice as likely as the hall) and climb up to them. A wall only takes up
its own blocks: buildings inside it, beds, trees and plans are unaffected (`Building.ring/occupies/overlaps`). The
table's kind button opens a list in three columns (homes and the town, workplaces, land) with the job each is for;
changing to a kind of land lays it out at once (working out its ground afresh), and a design of another kind of land
won't replace a finished building in its site.

**Market squares and roads.** Two more kinds the `Yard` draws. A **market square** (`stad:stalls`) is levelled and
paved; three-by-three stalls stand round its edge facing in (as many as fit a block apart: along the back, along the
sides when it's 11 wide, along the front either side of the way in when it's 11 deep), each with corner posts, a wool
awning, a barrel at the back, a top-slab counter at the front and a `stad:work` spot between; a bell stands in the
middle. It's a merchant's workplace (one slot a stall), and `Workplaces.spot(b, n)` gives the nth worker their own
stall. A **road** (`stad:road`) is drawn as `stad:road` markers (they're the input, kept when it's laid out again):
the generator paves each marked column, widened to the road's width, on the land as it is (planks over water), clears
two blocks above it, and puts lamp posts beside it every eight blocks. It only paves ground-like tops (earth, stone,
sand, paving) and skips the columns where the town's other buildings stand (`Project.keepOut`, worked out with the
terrain), so it stops at walls and never paves a chest or the town hall. `Routes` routes one: the ends are the doors
and gates of the town's buildings in the site (the first cell outside each), the town hall, and runs of path (or the
town's road) just outside the site's edge; it grows a tree from the first (or from what's drawn already), joining the
nearest end each time with a Dijkstra over the columns (climbing more than one block is out, buildings with room for
the road's width are out, water, trees and turns cost more, existing paving less). The screen's **Road** tool draws
4-connected lines between clicks; the server lays the road again after each change and the player's undo stays. A
road is a "ring" like a wall (only its own blocks are its); roads may cross roads. Planned, its paved blocks become
`stad:road` markers, and `Roads` knows the town's finished road blocks: citizens on one get +30% speed.

**Roads for the whole town.** `TownRoads` (the town hall's Lay roads, the table's Whole town, `/stad colony roads
[paving]`) routes over the town's whole claim, reading the land from the world (the heightmap, down past trees, leaves,
plants and barriers), not a site: `Routes.Land` is the shared column grid (ground, blocked, cost), `Routes.join` the
tree-growing, `Routes.doors` the ends (doors and gates in a building's steps, or in the world for a finished one, else
the middle of its side nearest the hall; the hall). It grows from the town's existing road markers, so pressing it
again adds only what's missing, and plans one road building ("Roads", then "More roads") whose blueprint holds the
paving, the clearing above it (a tree's whole trunk) and the lamp posts. Sites can be any size on the town's land:
positions are packed nine bits a side (`Project.key`/`col`), and tables saved with the old six-bit packing are
converted when they load.

**Stad's own look.** Screens share a theme (`client/ui/Theme`, `StadButton`): walnut panels with brass trim round dark
felt, brass title plates, wells for lists, wood buttons and tabs, brass scroll bars, and 16 by 16 icons for every job
and the town hall's tabs, 9 by 9 ones for inline states. They're GUI sprites (nine-sliced where they stretch). The art is
made by script so it can be redone, and kept as sources so it can be changed by hand: `tools/art/textures.py` draws every
texture as named layers, `paint.py` runs in Krita (kritarunner) and saves each as a layered `.kra` in `art/krita` and a
PNG in the assets; `models.py` drives Blockbench over its debugging port to build the block models (the architect's
table as a drafting table, the town hall as a panelled desk with a ledger) and saves each as a `.bbmodel` in
`art/blockbench`, a Java block model and a preview. Both blocks face the player who places them.

### 6.12 The trades: carpenter, mason, weaver, beekeeper

**Crafting trades.** `citizen/Crafts` defines a `Trade` for each of the carpenter (sawmill: crafting table), the mason
(stoneworks: stonecutter, plus a furnace for smelting) and the weaver (loom house: loom): what it makes (by item tags,
or for the mason stone, brick and glass blocks), the stock it keeps (planks 64, sticks 32; stone 32, stone bricks 64,
glass 16; beds 2) and what it makes things from (for the "nothing to make X from" line). `Crafts.how` finds a batch:
the stonecutter first (for the mason), then crafting through `Smithing.bill` (which makes planks and sticks on the
way), then the furnace; up to eight at a time. `CrafterGoal` plans every 50 ticks: the town's requests (what waiting
buildings are missing) that the trade makes come first, then the stock, counting what's in the stores and in its own
pockets; it fetches the bill, works at the bench (or furnace), and takes the result to the town hall. The workplaces
need walls and a roof plus their bench (the table furnishes it when generating) and are in every pack.

**Beekeeper.** The apiary is land (`stad:apiary`, a `Yard` kind): levelled, fenced with a gate, beehives every three
blocks along its middle facing the gate, an optional path from the gate, and flowers on every other block, which
become `stad:flower` land markers for the beekeeper to sow. Builders make a hive from six oak planks
(`Placement.itemFor/cost`). `BeekeeperGoal`: harvest a full hive (honey bottle with a glass bottle from the stores,
else three honeycomb; the honey level is reset without angering the bees), bring bees to a hive with fewer than two (a
wild bee within 48 blocks is given the hive; with none, a swarm settles in once a day when there are flowers near),
and sow the flower markers. One beekeeper keeps all of an apiary's hives.

**Tools.** `Tools.of`: carpenter axe, mason pickaxe, weaver and beekeeper shears; the town's tool requests and the
smith's "nothing to make one from" name everyone short of one (`Tools.whoNeeds`). A crafter doesn't start a batch when
the town hall (or warehouse) has no room for what it makes, or its pockets can't hold it, and says the hall is full.

### 6.13 Gatherers, deep mines and warehouse shelves

**Gatherer.** `GatherGoal` (job `gatherer`, no workplace, tool a shovel): first anything that's lain on the town's land
for 30 seconds (an `ItemEntity` older than 600 ticks); then what the town has asked for that the land gives, else the
stock (`KEEP`: dirt 64, sand 32, gravel 32, clay balls 16, sweet berries, sugar cane and wheat seeds 16). It scans the
columns round itself nearest first, within 48 of the town hall and on its land, not in any building's bounds (two
blocks round them), a ring building's blocks, or near the town hall. `FINDS`: DIG (grass/dirt/podzol, sand, red sand,
gravel, clay), BERRIES (a sweet berry bush aged 2 or more, set back to 1), CANE (sugar cane and cactus cut from the top
down to the bottom block), PICK (short grass and ferns for seeds, small flowers, pumpkins, melons, mushrooms, dead
bushes for sticks). Ground is only dug when no neighbour's ground is higher (so a dug column waits for those round it)
and there's no water beside it; clay under up to two blocks of water is fine. Drops come from the game's loot with the
gatherer's tool.

**Mines.** `Mine.Pit` has `tunnels` (a shaft's). Shafts go down `shaftDepth` layers (default 384: to bedrock; the loop
stops at the world's bottom and bedrock isn't diggable); a quarry's depth `Mine.BEDROCK` (0) means the same. At each
`Mine.LEVELS` height, once the shaft's layer there is dug, `Mine.tunnels` gives the tunnels' blocks before the next
layer: east and west corridors 24 long from the shaft's edge, side tunnels every 3 blocks off them 12 long north and
south, then north and south corridors; each column head-high block first, then the feet, a cobblestone floor where
it's open below, a wall torch every 6 blocks, stopping at the town's land's edge. `MineGoal` keeps a queue of ore
(`Tags.Blocks.ORES`) seen beside each block it digs and digs it next, following the vein within 3 blocks of where it
was first seen.

**Warehouse shelves.** `building/Warehouse`: `Shelf` (OTHER, BUILDING, WOOD, ORE, FOOD, FARM, CLOTH, TOOLS), `of(stack)`
and `of(container)` (from its custom name, or a word in it). `label` runs with the town's upkeep: each finished
warehouse's racks (containers stacked in the same columns, a double chest's halves together) that aren't a shelf yet
get the one with fewest racks, as their custom name (through the block entity's saved form: there's no setter) and on
a sign just above. `ColonyStorage.put` on storage with shelves: onto same stacks on the item's shelf, then empty slots
on it, then OTHER or unnamed containers, then anywhere. `CourierGoal`, with no request or haul, looks every 40 ticks
for: the town hall with fewer than 6 free slots (it takes up to four stacks of what isn't food, a tool, requested or
missing from a building), else a stack on the wrong shelf whose own shelf has room. The great warehouses (packgen
`furnish_great_warehouse`, 13 by 11) and the generator's warehouse racks (two high round the walls, a wall sign over
each where there's solid wall behind) make room for it.

## 7. Conversation and voice (provided by TWT)
- TWT already handles all of this:
  - Typed chat with targeting (look at someone, say their name, or keep talking to whoever you were talking to).
  - Streamed replies, group turn-taking and the echo filter.
  - Voice through Simple Voice Chat + Sipher for speech recognition, and Qwen3-TTS or Kokoro for TTS.
  - Positional audio from the entity, and subtitles.
- Stad citizens get all of it as soon as `StadAdapter` is registered.
- Stad feeds in its own context through prompt contributors: job, colony, home, standing, the current build draft and
  the result of the willingness check.
- New in Stad: an in-game **chat screen** (opened by right-clicking a citizen) showing the history, the citizen's mood
  and relationship, and buttons for "plan a building", "give job", "assign home". It sends lines into TWT's
  conversation pipeline like typed chat does.

## 8. Changes to they-will-talk (TWT 0.3: public API)

TWT is built to wrap *other* mods' villagers through internal adapters. To be a dependency it needs a stable public
package, `dev.eiriksb.theywilltalk.api`, versioned with semver. What's needed, roughly in the order Stad needs it:

| # | API | What it does | Today in TWT |
|---|---|---|---|
| 1 | `TheyWillTalkApi` entry point + `TwtReadyEvent` | Lets other mods reach TWT's services once the server is up | everything hangs off the `TheyWillTalk` instance |
| 2 | **Extensible villager kinds** | `VillagerKind.register("stad", "Citizen")`. The DB already stores the kind as text, so no migration is needed | `VillagerKind` is a fixed enum |
| 3 | **Public adapter registration** | `api.registerAdapter(adapter, priority)` | adapters are added inside `Integrations` |
| 4 | **Prompt contributors** | Add sections to a villager's prompt for each turn (async, with a timeout) | `PromptBuilder` is closed |
| 5 | **Conversation hooks** | `beforeReply(turn)` can classify the line (is it an order? a build request?) and add facts, such as the willingness outcome, so the reply matches. `afterReply(turn, reply, emotion)` runs after it. A handler can also take over a turn (the builder's planning conversation) | `ConversationManager` is closed |
| 6 | **Reflection fields** | Add-ons add fields to the single reflection call (`promise`, `standing_cause`, `order_given`...), so no extra LLM call is needed | fixed schema with three fields |
| 7 | **Memory API** | Add and query memories with subject, importance and source. Villager↔villager relations. Gossip propagation hook. Consolidation | `Store` with player-only memories |
| 8 | **LLM access** | Public `LlmClient` with priorities (add `PLANNING`). Separate **chat** and **planner** model endpoints. An `InferenceBackend` interface (local, external, client-lent GPU). (Done: `LlmApi` in v4, the planner model and lent GPUs in v6, M10) | one model, internal |
| 9 | **Events on the game bus** | `ConversationStarted/Ended`, `VillagerSpoke`, `MemoryAdded`, `AmbientChat` | none |
| 10 | **Dashboard extension** | Register API routes and web pages/tabs (colony, build queue, standing, wars) | fixed routes |
| 11 | **Face provider** | Let Stad draw its citizens' faces for the dashboard and BlueMap | MCA/MineColonies/vanilla only |
| 12 | **Errand kinds** (optional) | Register new errand types, or let Stad use its own request system instead | fixed kinds |
| 13 | **Voices and chat (API v5, done in M9)** | An adapter can give a villager its own voice (`VillagerFacts.voiceDesign`, pitch, speed); `talk` and `history` for a mod's own chat screen | TWT designs voices from the villager's role and personality |

**Rules for the API:**
- Keep it small, take interfaces in and hand records out, and never expose internal classes.
- The generic pieces (memory upgrades, villager↔villager relations, planner model, inference backends) are TWT
  features in their own right, so vanilla, MCA and MineColonies villagers benefit too.

**Development setup:** use a Gradle **composite build**, `includeBuild("../they-will-talk")` in Stad's
`settings.gradle`, so changes in both repos are compiled together and Stad's dev runs load the TWT under development.
TWT still needs Sipher built next to it, or `-PwithSipher=false`. For releases, Stad's `neoforge.mods.toml` declares
`theywilltalk` as `required` with `versionRange="[0.3,)"`, and the Modrinth/CurseForge pages list it as a required
dependency.

## 9. Project layout

```
Stad/
  settings.gradle (includeBuild ../they-will-talk) · gradle.properties · gradlew
  mod/src/main/java/dev/eiriksb/stad/
    citizen/       entity, genetics/skins, needs, schedule, family        (client/ for renderer + model)
    agent/         AgentBrain, ThinkScheduler, Perception, Tools, Plans   (uses TWT's LlmClient + memory API)
    twt/           StadAdapter, PromptContributors, ConversationHooks, ReflectionFields, FaceProvider, dashboard pages
    colony/        Colony, ColonyManager, TownHallBlock, Ranks, Requests, Warehouse, Standing, OrderService
    job/           Job, BuilderJob, FarmerJob, LumberjackJob, CourierJob, GuardJob ...
    building/      Pack, PackRegistry, Blueprint, BlockRoles, Palette, Placement, BuildOrder, HouseScanner
    planner/       BuildSpec + schema, Generator (Footprint, Rooms, Roof, Openings, Furniture), PreviewSync
    raid/          MobRaids, WarStance, PvpRaid, Snapshots, Repair
    data/          StadStore (stad.db through TWT's Database)
    net/           packets
    client/        screens (chat, colony, planner, pack editor), ghost renderer, scan wand
  mod/src/main/resources/data/stad/stad_packs/   bundled packs (e.g. "Medieval", "Nordic", "Desert")
  mod/src/main/resources/assets/stad/web/        dashboard pages, served through TWT's dashboard
```

## 10. Roadmap

Build a **vertical slice first**: one builder you can talk to, who remembers you and builds a hut from a bundled pack.
Everything after that widens it.

| # | Milestone | Done when |
|---|---|---|
| **M0** | Foundations + TWT API | **In TWT:** API package with items 1–5 from §8 (entry point, extensible kinds, adapter registration, prompt contributors, conversation hooks), released as TWT 0.3.0-alpha. **In Stad:** project setup with a composite build against `../they-will-talk`. Dev server and two dev clients run with both mods. The Models page (TWT) installs the model. |
| **M1** | Citizen + talk | Custom citizen entity (human model, generated skin and name). Spawn egg/command. `StadAdapter` registered, so citizens talk, remember and show up in TWT's dashboard with no extra work. A prompt contributor adds job/colony facts. Works with two players talking to different citizens at once. **In TWT:** memory API and villager↔villager relations (items 6–7). |
| **M2** | Colony core | Town hall founds a colony with ranks. Citizens get assigned beds/homes and follow a day schedule with basic needs. Colony GUI. All orders go through one `OrderService` (accepts everything for now, but every order is logged). |
| **M3** | Packs + builder (**vertical slice**) | Pack format and loader, one bundled pack with a builder's hut, home and warehouse. The builder takes a BuildOrder, gets materials from a chest or asks for them, and builds layer by layer. You can ask the builder in chat about progress and what's missing. |
| **M4** | Jobs + logistics | Courier/warehouse request system. Farmer and Lumberjack. Morning-plan and react loops drive priorities. Happiness from needs and workload. |
| **M5** | Social life + leadership | Citizen↔citizen relations, gossip spreading along friendships, ambient chatter, group conversations, courting/marriage/children, consolidation summaries (moved to later: not done in M5). **Standing and willingness:** grudging work, refusals with reasons, negotiation turning into requests, strikes and leaving, difficulty setting, standing panel in the GUI. |
| **M6** | Talk-to-plan | BuildSpec schema. *Pick & adapt* mode first, then the procedural generator. Ghost preview with material list. Revising by conversation. Save to "My pack". (Done: both modes; the builder refuses approvals like other orders; drafts avoid what's already built. Not done: "facing the lake"-style placement, material substitution questions, moved to M7 with palettes.) |
| **M7** | Custom packs + adoption | Scan wand, role/marker editor GUI, palette restyling, house adoption with "what's missing" feedback, sharing packs as zips. (Done, plus M6's leftovers: "facing the lake" placement and material substitution questions. Markers are kept and read but only `stad:storage` changes behaviour so far; markers are placed with vanilla structure blocks, not in the editor. Upgrading adopted buildings with the generator is not done.) |
| **M8** | Raids + rivalry | Guard job, barracks and watchtowers. Mob raids with warnings, sheltering, snapshots and repair orders. Claim protection. Poaching (with a switch for each colony). War stance for each colony (peaceful/open, with cooldown). PvP: declaring war, raid windows, banner capture, capped loot, truces, envoys. Citizens' opinions of war feed into standing. (Done. Raiders are vanilla pillagers, plus vindicators on hard; biome raider factions, fire, alliances and tribute are not done. Snapshots and raids in progress aren't saved over a restart: a war raid cut off by a restart counts as held. No town hall banner block yet: the "banner" is the ground by the hall. Not on the dashboard or BlueMap yet.) |
| **M9** | Voice polish | Voice already works through TWT. This milestone adds voice designs from a citizen's genes and age, and voice support in the planner conversation and chat screen. (Done, with TWT API v5: adapters give voices, `talk`, `history`. Adults don't age yet; only children's voices change. The chat screen and the spoken builder's shorter replies haven't been seen or heard on a real client.) |
| **M10** | Scale + hosting | Think budget/LOD tuned for 50+ citizens. **In TWT:** client-lent GPU backend and planner-model setting (item 8). Also a performance pass (pathing, chunk loading of colony areas, raid mob counts). (Done, with TWT API v6: `planner()`, `forPlayer()`, `backlog()`. Citizens near players get model day plans, budgeted per town; the rest a routine plan. Workers re-path at most once a second and lumberjacks spread over the trees, which took 66 citizens from 7.5 to 1 ms per tick; 125 cost about 3 ms. Towns stay loaded through NeoForge forced chunks while a member is online. Lent GPUs and the planner model were tested live with a dev client and a second model process.) |
| **M11** | Content + release | More jobs, two or three more style packs and raider factions, balancing, docs, CI build (TWT's workflow). (Done as 0.1.0: cook, fisher, shepherd and miner (a spiral-step shaft under the Medieval pack's Mine); the Fjord and Oasis packs plus a Medieval kitchen and mine; Norse raiders, marauders and bandits by biome, each raid with a named leader; balancing from live raid trials (raider gear is for show, trained guards, falling back away from raiders); a player's guide and changelog. No CI build: the user asked for no GitHub Actions in Stad. Not published to Modrinth/CurseForge.) |
| **M12** | Architect's table | The table block and its project, the site from the scan wand or typed corners, the 3D view (cached section buffers, orbit camera, layer cut-away, faded surroundings), generating a fitted building (generator up to 32 × 32), describing changes to the planner model, the hand-edit tools with undo, edits kept as a layer over the generated building, markers in the editor, the live requirements checklist, Save to My pack and Plan it here (with clearing steps). Game tests for capture, edits, server checks and fitting; seen on a real client. (Done. Seen on a dev client at 1280x720 (GUI 427x240): the view, generating, the AI's revisions, placing, the box tool, the picker, markers, undo, the cut-away, planning and saving all work; driven from the console with `/stad dev ui`. Not done: the requirements that depend on the land round the site ("by water") come with M13's workplaces; watchers see the design as it was when they opened the table, not the editor's changes live; translucent blocks aren't depth-sorted; markers only go in open spaces, so a `stad:storage` marker can't sit on a chest; the copy tool copies from the site and its margin only.) |
| **M13** | Workplaces + free build | Farmhouse, lumber camp, fishery and barn types; worker slots and assignment; the day, deliveries, tools and work area centred on the workplace; the work-speed and happiness bonus; workplaces in the town hall screen, chat screen and prompts. Bundled designs of the new workplaces in each pack. Game tests for each job with and without a workplace. **Free build** in the table's site (above), with its safeguards and crash recovery. (Done. Workplaces: the four types with their needs (what's round them counts as a wish, not a need), slots, assignment, moving by talk or command, the morning walk, work areas, the speed and happiness bonus, couriers carrying hauls on, grumbling, prompts, the town hall and citizen screens, twelve bundled designs, and the table furnishing and checking workplaces. Free build: seen on a dev client driven with `/stad dev ui` (now with world input). It first built in the real site and put it back afterwards; after M13 it moved to the drafting world (a copy of the site in a void dimension, kept across logouts and restarts), and the table's screen was decluttered (the view takes up the screen, generating and asking moved into a drawer). Seen live: the copy, placing, the invisible walls, B and Keep it, the table reopening with the change, rejoining mid-build; the game test covers keeping, throwing away and a restart. Not done: tools aren't kept at the workplace (citizens' tools are still the town's, conjured as needed); shepherds don't stay near their barn's pen; grass under a free-built floor is ignored, so are opened doors.) |
| **M14** | The later jobs | Skills that grow with work; real tools that wear out and are kept at the workplace; the smith (mending, making and smelting), the healer (and illness), the merchant (trading the town's goods for emeralds through the game's trading screen) and the teacher (school for children, who then learn faster). Smithy, infirmary, market and school types with bundled designs in each pack; table support; prompts and screens; game tests (§6.9). (Done. Game tests for skills, a rod wearing out and the town asking for another, tools going back to the workplace at night, the smith making a stone pickaxe from cobblestone and planks while mending an iron axe and smelting raw iron, the healer curing and mending, illness passing, the merchant's offers and trades moving real goods, and a child's school day. Seen live on a dev client: the market in the game's trading screen and a trade moving cobblestone and emeralds in the town hall, the chat screen's skill line and Trade button. Not seen live: the smith, healer and teacher at work (only in game tests). The smith works only from a smithy; the healer uses no materials; prices are a fixed table, the same for every player.) |

## 11. Risks

| Risk | Mitigation |
|---|---|
| Small models give bad or unsafe tool calls | Grammar-constrained JSON. Every call checked server-side. Tools limited to the NPC's role and the player's permissions. A fallback line when a call is rejected. |
| LLM latency stalls the game | Nothing on the tick thread waits on the LLM. Priority queue. Behaviour runs fine without agent input. |
| VRAM: chat model + planner + TTS together | TWT already starts and stops the VoiceDesign server on demand. Load the planner model the same way. |
| Memory grows without bound | Importance scores and evening consolidation into summaries. Old low-importance rows are folded away. |
| Builder/pathing bugs on odd terrain | Clear the site first. The builder can place scaffold blocks and teleport when stuck (as MineColonies does). Lots of automated tests on the generator and placement. |
| Refusal feels unfair or annoying | Decided in code with a visible reason and score. Safety limits always apply. The *Loyal* difficulty turns it off. Negotiation always gives you a way to fix it. |
| Two players giving conflicting orders | Ranks decide who may give an order. If two orders conflict, the most recent order from the highest rank wins, and the citizen says so ("Astrid told me to farm today"). |
| PvP raids used for griefing or against offline players | Opt-in for each colony (peaceful by default, war only between two open colonies, cooldown before going back to peaceful). Also raid windows, offline protection, warning period, only certain blocks breakable, snapshot repair, capped loot, truce timers. All of it is server config. |
| Poaching feels like theft | It only works on citizens who are already unhappy, the citizen warns you first, and family ties hold people back. It can be turned off per server. |
| Scope (this is two big mods in one) | Strict milestones and the M3 vertical slice. Defer complex crafting chains and research trees. |
| TWT API changes break Stad | Small API package versioned with semver, composite build so both repos are compiled together, and Stad declares a version range. Both repos are yours, so they can change in step. |
| Players must install TWT and its models | The TWT Models page already makes this one click. Stad's first-run screen checks for TWT's runtime and links to it. |
| The 3D editor is slow or fiddly (big sites, picking, block facing) | Sites capped at 32 × 32 × 32. Sections cached in vertex buffers and rebuilt only when edited. Picking walks the design grid, not the rendered model. Facing and joining come from the blocks' own placement and shape rules, run on the design's blocks. Undo for everything. |
| License | Don't copy MineColonies/MCA/Structurize code or assets (GPL-3.0). TWT is MIT and yours. |

## 12. Open questions
1. ~~How should Stad relate to TWT?~~ **Decided: TWT is a required dependency with a public API (§8).**
2. ~~Multiplayer or singleplayer?~~ **Decided: both, server-authoritative from M1.**
3. ~~Should citizens be able to refuse orders?~~ **Decided: yes, if you're a bad leader (§6.6).**
4. ~~Can rival colonies recruit unhappy citizens?~~ **Decided: yes, on by default (§6.6 Poaching).** Mob raids and
   PvP raids are also in (§6.7).
5. ~~Default for PvP raids?~~ **Decided: cautious. Each colony opts in, colonies start Peaceful, and war only happens
   between two open colonies (§6.7).**
6. Should the bundled art (skins, bundled packs) be made in-house, or should community-made packs be invited early?
