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
