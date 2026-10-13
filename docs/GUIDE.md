# Stad: a player's guide

## Getting started

1. Install NeoForge 21.1 for Minecraft 1.21.1, then **They Will Talk** (0.3.0-alpha.6 or newer) and **Stad**. On a
   server, both go on the server; players need Stad on their own computer too (for its screens and the building
   previews), and Simple Voice Chat plus Sipher if they want to talk with their voice.
2. The citizens think with a small AI model that runs on your own GPU. Open a world (or start the server), run
   `/twt dashboard`, and click **Install recommended** on the Models page. Until it's installed, citizens live and work
   but don't talk.
3. Craft a **town hall**: three bookshelves on top, planks, a bell and planks in the middle, three planks below. Place
   it where your town's middle should be. Its land reaches 48 blocks round it.

## Your first citizens

Newcomers move in every few minutes while there's a **free bed** on the town's land, so build or adopt a house with a
bed or two first. Everyone eats from the **town hall**: right-click it and put food in its storage.

Right-click a citizen to talk to them and give them a **job**. Type to them in the chat screen, or speak with your
voice chat key while looking at them. They remember what you say and what you do.

## Jobs

| Job | What they do | What they need |
|---|---|---|
| Builder | builds what officers plan, block by block | a planned building, and its materials in the town hall |
| Farmer | harvests and replants the crops | farmland on the town's land |
| Lumberjack | fells natural trees, top down, and replants | trees on the town's land |
| Courier | carries food and materials from the warehouse to where they're needed | a warehouse |
| Guard | patrols, fights monsters and raiders | a sword (and armour) in the town's stores |
| Cook | bakes bread from wheat, cooks meat, fish and potatoes, and brings it to the town hall | a furnace, smoker or campfire on the town's land |
| Fisher | fishes from the shore | a pond or river on the town's land (eight blocks of water or more) |
| Shepherd | shears sheep, feeds the farm animals so they breed, butchers the extras, picks up eggs and wool | sheep, cows, pigs or chickens on the town's land |
| Miner | digs the town's mine deeper, layer by layer, for stone, coal and ore | a **Mine** from the Medieval pack |

Workers bring what they gather to the **warehouse** (or the town hall when there's none). Late in the afternoon they
bring the day's work in.

**Feeding the town.** Wheat only feeds anyone once a cook bakes it (three wheat to a loaf), and cooked food fills a
citizen far more than raw. A farmer and a cook keep a small town fed; add a shepherd, a fisher and a courier as it
grows. The town hall asks for food when it runs low (`/stad colony requests`).

**The shepherd** breeds each kind of animal up to six (`herdSize`) with the town's wheat, carrots and seeds, and
butchers them once there are four more than that. Animals with a name or on a lead are someone's pets and are left
alone.

**The miner** digs a 5x5 shaft under the mine's headframe. One block of each layer stays as a step, each a block
further round the wall, so the steps spiral down and the miner walks to the bottom and back. They put torches on the
wall as they go and wall off any water or lava they break into. A mine stops at 48 layers (`mineDepth`). On a slope,
where the shaft starts above the ground, they put the top steps in first.

## Building

**Building Plans** (two paper and a coal): right-click the ground to choose a building and a style, see where it
goes, and plan it. **Or talk to your builder**: "a small stone cottage with two bedrooms and a chimney". They draw it
up, a ghost of it appears nearby, and you change it as you talk until you say "build it".

The packs: **Medieval** (timber and cobblestone under hip roofs: cottage, builder's hut, warehouse, kitchen, mine),
**Fjord** (laid log walls on stone footings under turf roofs: stue, stabbur, builder's smie) and **Oasis** (sandstone
with flat roofs and parapets: house, storehouse, workshop). Any building can be built in any of the seven styles.

![The Fjord and Oasis packs, and the Medieval kitchen and mine](packs.png)

**The architect's table** (Building Plans on top of a cartography table) lets you design for one exact spot. Mark
two corners with the scan wand and right-click the table with it. You see the site in 3D: drag to turn it, scroll to
zoom, shift+scroll to cut it off at a layer and look inside. Choose what kind of building it is, a style, floors and a
roof, and click **Generate**; then change it block by block (the tools are along the bottom; right-click removes,
middle-click picks a block, R turns it, E opens the block list). The line at the bottom left says what it still needs.
**Plan it here** hands it to the builders; **Save to pack** keeps it for anywhere.

![The architect's table: a generated cherry house on a hillside site](table.png)

Built a house yourself? Stand in it and tell a citizen "this is your home now". The **scan wand** (an amethyst shard
on top of two sticks) saves your buildings into **My pack**.

## Leading

Every order is checked against your rank and the citizen's opinion of you. Your **standing** with the town rises when
they're fed, housed, safe and treated well, and falls when they go hungry, get hurt or are let down
(`/stad colony standing` says why). A respected leader is obeyed; a doubted one gets slow, grudging work; a resented
one hears "no", with a reason, or "only once I've eaten". Miserable citizens leave, and an unhappy town goes on strike.

## Raids

Bigger towns get raided now and then at dusk. Who comes depends on the land round the town hall:

| Faction | Where | Look |
|---|---|---|
| Norse raiders | taiga, snowy and cold lands | iron helmets, axes, the leader with a shield |
| Marauders | deserts, badlands, savanna | sand-coloured leathers, golden swords and bows |
| Bandits | everywhere else (or pillagers) | dark leathers, swords and bows |
| Pillagers | everywhere else (or bandits) | the game's own, with vindicators on hard |

Every raid but the pillagers' has a named leader, tougher and better armoured than the rest. A citizen spots them and
shouts which way they're coming; guards take swords and armour from the town's stores and fight; everyone else
shelters indoors, or runs to the far side of town when there's nowhere to hide. Afterwards the builders repair the
damage, and everyone remembers who fought.

Two guards with iron swords should see off a first raid. Build barracks and watchtowers as the town grows, keep spare
swords in the town hall, and give guards houses near the edge the raiders come from.

Wars between players' towns are opt-in for each town; see the README.

## Settings

`config/stad-common.toml` (and They Will Talk's own `config/theywilltalk-common.toml`). The ones you're most likely to
change:

| Setting | Default | |
|---|---|---|
| `colonies.claimRadius` | 48 | how far a town's land reaches |
| `colonies.arrivalMinutes` | 5 | minutes between newcomers |
| `colonies.leadership` | NORMAL | LOYAL (they always obey), NORMAL, HARSH |
| `jobs.herdSize` | 6 | animals of each kind the shepherd keeps |
| `jobs.mineDepth` | 48 | layers a mine goes down |
| `raids.mobRaids` | NORMAL | OFF, EASY, NORMAL, HARD |
| `raids.pvpRaids` | OPT_IN | OFF, OPT_IN, FORCED |
| `thinking.thinkRadius` | 48 | citizens this close to a player plan their day with the AI |
| `performance.keepLoaded` | WHILE_ONLINE | NEVER, WHILE_ONLINE, ALWAYS |
| `raids.raiderFactions` | true | off: always pillagers |
| `raids.maxRaiders` | 16 | the most raiders in one raid |
