# Changelog

## Unreleased

**The architect's table**
- A new block (Building Plans on top of a cartography table) for designing a building for an exact site: mark it with
  the scan wand, see it in 3D, generate a building of any kind that fits it (up to 32 by 32), change it block by block
  with a full set of tools, ask the planner model for changes, see what it still needs, then plan it for the builders
  or save it into My pack. Officers edit, members watch, and the table keeps its project when it's broken.
- The generator draws buildings up to 32 blocks long (still 17 when talking to a builder), and a building on the site
  can be redesigned in place.
- **Free build**: Build in it takes you to the drafting world, an empty void with a copy of the site and the land round
  it (in the real place's biomes, with a grid showing where you can build), to build it yourself in creative. Press B and Keep it: what you build becomes the design, and you're back at the
  table with your own things. The real site is never touched, nothing comes back with you, and logging out or a
  restart doesn't end it.
- The table's screen is mostly the 3D view now: the tools along the bottom, the name and kind top left, and
  generating and asking for changes in a drawer (Generate ▾).

**Workplaces**
- Every job works from a building: new **farmhouse**, **lumber camp**, **fishery** and **barn** types (in every pack),
  plus the kitchen, mine, builder's hut, warehouse and barracks. Workers get the nearest one with room ("work at the
  lakeside fishery" moves them); there they start the day, work the land round it, work a quarter faster and are
  happier, and couriers carry their haul on. Without one they grumble.

**The later jobs**
- **Smith**, **healer**, **merchant** and **teacher**, with a smithy, infirmary, market and school in every pack (twelve
  new designs), all plannable at the architect's table.
- **Skills**: citizens get better at each job they do, from novice to master (up to a fifth quicker).
- **Real tools**: farmers, lumberjacks, miners, fishers and shepherds take a real tool from the stores, wear it out
  and put it back in their workplace at night; without one they make do, slower, and the town asks for one. The smith
  makes them (from the game's own recipes), mends them and smelts ore.
- **Illness**: citizens sometimes fall ill and stay in bed for a few days; the healer cures them sooner and mends the
  wounded.
- **The market**: trade with the town through its merchant (sneak and right-click, or Trade on their card), for
  emeralds; every trade moves goods in and out of the town's real chests.
- **School**: children go to school while there's a teacher, and grow up quicker to learn.

**Farms at the architect's table, and a new look**
- The architect's table designs land as well as buildings: pick **Field**, **Pen** or **Grove** as its kind and open
  **Lay out ▾**. A **field** fills the site with farmland round water channels (or holes) spaced so every plot stays
  wet, with the crop you choose (or mixed strips), a fence, a path and lamps if you like. A **pen** is a fenced yard
  with a gate for the animals you choose, with a shelter, hay and water. A **grove** is saplings in rows, as far apart
  as you like. Change anything by hand afterwards, including painting farmland, water (now allowed in designs) and
  crops; the checklist says whether every plot has water, the fence is closed, and the saplings have room.
- Planned, the land goes to the workers: the farmer digs the water, tills and sows each plot with the crop it asks for
  (fetching it from the town hall first); the lumberjack plants each spot with the sapling it asks for and fells the
  trees as they grow; the shepherd leads the pen's animals in through its gate (loose ones in town first, then a pair
  from outside). The builders level the ground and put up the fence, path and the rest.
- Land for the other jobs too: a **quarry** (an open pit of any size the miner digs down, 8 to 48 layers, with steps
  round its edge and a fence round it), a **dock** (a pier out over the water, with spots the fishers fish from), a
  **guard post** (a platform with a ladder, railing and lamp; guards patrol to it and keep watch from the top) and a
  **wall** (logs, stone bricks or cobblestone round the site, with a door and watch spots on the corners; it goes round
  whatever is already inside).
- **Choosing what to design** is a list now instead of a button you click through: homes and the town's buildings,
  workplaces (one for every job: builder's hut, kitchen, mine, farmhouse, lumber camp, fishery, barn, smithy...) and
  land, each with its job's icon and, on hover, who it's for and what it needs. Changing to a kind of land lays it out
  at once.
- The table won't put a different kind of land in a finished building's place (a quarry over a field); redesigning a
  field as a field still works.
- **Market squares**: an open-air market for the merchants. The site is levelled and paved, with three-by-three stalls
  round its edge facing in (corner posts, an awning in the colour you choose or red-and-white stripes, a barrel at the
  back, a counter at the front, a lantern under the awning) and a bell in the middle. A merchant keeps each stall: the
  square is a merchant's workplace like the market building, and with more than one merchant each takes their own
  stall.
- **Roads**, drawn or routed. Pick **Road** as the kind: the table joins up what's in the site at once (the doors and
  gates of the town's buildings, the town hall, and paths or roads running up to the site's edge) the easiest way over
  the land, round buildings and anything standing in the way, never up more than a block at a time, along roads that
  are already there where it can (**Route it** does it again). Or draw it yourself with the **Road** tool: click along
  the ground and each click draws on from the last; right-click stops, or takes the road off a block. Choose the paving
  (dirt path, gravel, cobblestone, stone bricks or planks; planks over water), the width (1 to 4) and lamp posts beside
  it. A road stops at the town's buildings and only paves the ground, so it never runs over a chest, the town hall or
  a roof. Roads can cross each other and run inside a wall. **Citizens walk about a third faster on the town's roads.**
- **Lay roads** (on the town hall's Buildings tab, and **Whole town** in the table's road drawer): roads joining every
  door and gate in town, and the town hall, over all of the town's land, planned at once for the builders. Choose the
  paving. Places with no door (a market square's open way in) are joined at the side facing the hall. Press it again
  after building more: it grows from the roads already there and only adds what's missing.
- A table's site can be as big as you like now, as long as it's all on the town's land (a generated building still
  fits in 32), for long roads and big fields. Tables saved before keep their designs.
- Fields, pens, groves and the rest are in a **Farms** tab of the Building Plans when saved into My pack (market squares
  and roads in **Town**).
- **A new look**: the town hall, the citizen's card, the Building Plans, the architect's table and the pack editor are
  dark walnut panels with brass trim, with icons for every job, wood buttons, tabs and scroll bars.
- **New models**: the architect's table is a drafting table with a blueprint, a roll of plans and an ink pot; the town
  hall is a panelled desk with the town's crest and its ledger lying open. Both face you when placed. Building Plans
  and the scan wand have their own textures.
- Builders ask for dirt, not grass or path blocks (which a survival player can't pick up), and turn it into what the
  design needs.

**Four new trades**
- **Carpenter** at a **sawmill** (a crafting table): saws the lumberjack's logs into planks and sticks, and makes the
  doors, fences, stairs, chests and the like the builders are waiting for.
- **Mason** at a **stoneworks** (a stonecutter and a furnace): burns cobblestone into stone, cuts stone bricks, slabs,
  stairs and walls, and makes glass from sand.
- **Weaver** at a **loom house** (a loom): makes the shepherd's wool into beds for new homes, and carpets.
- **Beekeeper** at an **apiary**, a new kind of land at the architect's table: a fenced meadow with beehives in a row
  and flowers between. The beekeeper sows the flowers, brings wild bees to the hives (or a swarm settles in now and
  then), and takes the honey into glass bottles from the stores, or as honeycomb. The builders knock the hives
  together from planks, since a new town has no honeycomb for the real recipe.
- The three crafting trades use the game's own recipes and make what the builders are short of first, then keep a
  small stock of the basics. Their workplaces are in every pack (nine new designs), can be generated at the table, and
  have job icons of their own. The table's list of kinds puts the workplaces in two columns when they don't fit.
- They all work with real tools, like the other jobs: the carpenter an axe, the mason a pickaxe, the weaver and the
  beekeeper shears. They take the best from the stores, wear it out and put it back at night; without one they work
  slower and the town asks for one ("1 shears for the town hall (for the weaver and the shepherd)"), which the smith
  can make.
- The crafting trades stop, and say so, when the town hall is full, instead of making what they can't put away.

**Gatherers, deep mines and great warehouses**
- A new **gatherer** job, needing no workplace: digs dirt, sand, gravel and clay from the land round the town, forages
  berries, sugar cane, seeds, flowers, pumpkins, melons and mushrooms, and picks up what's been left lying about
  (saplings dropped by the leaves, say). They gather what the town has asked for first, then keep a small stock (64
  dirt, 32 sand and gravel, 16 clay, berries, sugar cane and seeds). They only take the top off natural ground, never
  where the ground beside it is higher, so the land goes down evenly instead of getting pitted with holes; never in the
  town's buildings, yards or roads, nor beside water (except clay from the shallows). A shovel makes it quicker.
- **Mines go down to bedrock.** A mine's shaft keeps going until it gets there (`shaftDepth`), and a quarry can be laid
  out **to bedrock** as well as 8 to 64 layers.
- **Strip mining.** At the heights where the ores are (48, 16, 0, -16, -32 and -58) the miner tunnels out from the
  shaft before going deeper: two-high tunnels 24 blocks each way, with side tunnels every three blocks off the east and
  west ones, lit with torches, floored over caves, and on the town's land only. Any ore they uncover they dig out, the
  whole vein, as far as they can reach it.
- **Great warehouses** in every pack (Great Warehouse, Great Storehouse): double chests two high round the
  walls and barrels down the middle, about 2,000 slots. Warehouses generated at the table get racks two high round
  their walls too.
- **Shelves.** A warehouse's chests are each given a kind of goods (building blocks, wood, ores and metals, food,
  farming, wool and cloth, tools and arms, everything else), shown when one is opened and written on the sign over its
  rack. Goods go onto their own shelf; name a chest in an anvil ("Food") to choose its shelf yourself. With nothing to
  carry, the courier puts what's on the wrong shelf on the right one, and when the town hall is nearly full takes what
  it doesn't need (not its food, tools or what the builders are waiting for) to the warehouse.

**The university and research**
- A new town can build its homes, the builder's hut, the warehouse, the farmhouse and fields, the lumber camp and
  groves, the kitchen, roads and a **university** from the start. The rest it learns: a **researcher** at the
  university (a lectern and bookshelves; a University and a House of Wisdom in the packs) studies a topic at a time,
  using books and materials from the stores, and when it's done the town can build what it unlocks.
- Sixteen topics: Animal husbandry (barn, pen), Fishing (fishery, dock), Mining (mine, quarry), Carpentry (sawmill),
  Trade (market, market square), Masonry, Smithing, Weaving, Beekeeping, Medicine, Schooling and Defence (barracks,
  watchtower, guard post, wall) for buildings, some needing others first; and upgrades: Better tools and Mastery
  (workers a tenth quicker each), Surveying and Cartography (more land).
- The town hall's new **Research** tab shows what's learned, what's being studied and how far along, and what each
  topic takes and gives; officers choose what's next there. Locked buildings are greyed out in the building plans,
  with what to research, and can't be planned, adopted or designed at the table until they're learned.
- Towns founded before this know whatever they've already built or planned. Turn research off with `research`.

**Towns that grow**
- A town's land starts smaller (32 blocks round the town hall) and grows by 4 blocks with every home finished on it,
  up to 96, stopping where another town's land begins. Members are told when it grows, citizens can roam the new
  land at once, and the town hall says how far it reaches and how it'll grow. Towns founded before keep their land
  and grow from there.

**Placing buildings by hand**
- **Preview** in the building plans now shows the whole building, see-through, where it would go, instead of an
  outline, and you can move it before planning it, much like placing a Create schematic: the arrow keys move it,
  Page Up and Down raise and lower it, R turns it, right-click picks it up to follow where you look (and puts it down
  again), Enter plans it there and Backspace puts it away. It turns red, and says why, where it doesn't fit.

**English names, one pack fewer**
- The Fjord pack is gone (its buildings were doubles of the Medieval and Oasis ones under Norwegian names), so the
  building plans list each building twice instead of three times. Buildings already put up from it stay standing but
  aren't the town's any more.
- New citizens and towns get English names (Alice Thatcher, Ashford) instead of Nordic ones; the ones already named
  keep theirs.

**Easier to play**
- **Every citizen says what they're doing, or what's stopping them and what would fix it** ("No seeds to sow: put wheat
  seeds in the town hall", "Nowhere to cook: place a furnace..."), on their card, in the town hall and when you talk to
  them. A problem that lasts a minute is told to the town's leaders, once a day.
- **The town hall** has tabs: **Town** (next steps for getting a town going, who can't work and why, what the town
  needs), **Citizens** (what each is doing), **Jobs** (what every job does and needs, and whether the town has it) and
  **Buildings**.
- Hover over a job on a citizen's card to see what it does and needs; jobs the town can't support yet are greyed out
  and say what's missing. A citizen with no job takes their first one more willingly.
- **Building Plans** are grouped in tabs (Homes, Workplaces, Town, Fields), say what each building is for, and the list
  scrolls. Planning one tells you who'll build it and what to put in the town hall.
- **Farmers make their own fields**: with no field they mark one out (9 by 9) by their farmhouse or the town hall,
  level it, till it, dig a water hole in the middle and sow it, with seeds from the town hall or picked from the grass.
  Or plan a **Field** where you want it with the building plans; the farmers make it.
- **Builders** build what they can while they wait for the rest, make up the ground under a building (a slope, a dip,
  shallow water), fetch from the warehouse themselves, and won't build on open water. Cancelling a building no longer
  leaves its builder stuck on it.
- **Lumberjacks** plant a grove from the town's saplings when there are no trees; **shepherds** bring in farm animals
  from just outside town; **cooks** cook what's in the town hall too.
- Citizens walled in by a house going up round them get out to eat and sleep.

## 0.1.0 (first release)

Stad needs [They Will Talk](https://github.com/Eiriksb/they-will-talk) 0.3.0-alpha.6 or newer, NeoForge 21.1 on
Minecraft 1.21.1.

**Towns and citizens**
- Found a town with a town hall; newcomers move in while there are free beds. Players have ranks (owner, officer,
  member, visitor) and the town's land is protected.
- Citizens sleep in their own beds, eat from the town hall, plan their day each morning, spend evenings with friends,
  pass news along, court, marry and raise children who grow up. Each has their own voice, from their genes and age.
- Talk to any citizen by typing or with your voice; right-click one for the chat screen (who they are, what they think
  of you, what you've said, and the orders you can give).

**Jobs**
- Builder, farmer, lumberjack, courier and guard; new in this release: **cook** (bread from wheat, cooked meat, fish
  and potatoes at a furnace, smoker or campfire), **fisher** (from the shore of a pond or river), **shepherd** (shears,
  breeds and butchers the farm animals) and **miner** (digs the town's mine deeper, a layer at a time, down a spiral
  of steps).

**Buildings**
- Plan buildings by talking to the builder ("a small stone cottage with two bedrooms"): a ghost preview, changes as
  you talk, "build it". Building packs (Medieval, and new: **Fjord** and **Oasis**; the Medieval pack gains a
  **Kitchen** and a **Mine**), seven styles, the scan wand and pack editor, adopting houses you built yourself, and
  builders who ask before swapping materials.

**Leadership**
- Your standing with the town rises and falls with how they're treated. Respected leaders are obeyed; resented ones
  hear "no", with a reason. Unhappy citizens leave, and towns go on strike.

**Raids and rivalry**
- Mob raids that fit the land: **Norse raiders** in the cold north, **marauders** in deserts, badlands and savanna,
  **bandits** or pillagers elsewhere, each raid with a named leader. Lookouts warn the town, guards fight, the rest
  shelter, and builders repair the damage. Opt-in wars between players' towns, with raid windows, banner capture,
  truces, envoys and poaching.

**Big towns and hosting**
- A think budget for towns of 50 and more, `/stad perf`, towns kept loaded while their members are online, a planner
  model for building designs, and players lending their GPU to the server (through They Will Talk).

**Balancing in this release**
- Raiders' gear is mostly for show: how hard they hit and how much they take is the faction's (a raider with a
  player's iron axe used to cut down whole towns). Leaders keep real armour.
- Guards are trained fighters while they have the job: 30 health and harder blows. Badly hurt guards, and citizens
  with nowhere to shelter, fall back away from the raiders instead of to the town hall the raiders head for.
- Raids grow a little slower with the town (one more raider per four citizens rather than per three), capped by
  `maxRaiders`.
- The farmers' wheat only feeds anyone once a cook bakes it into bread, and cooking fills a citizen far more than
  eating raw (a baked potato five times a raw one).
