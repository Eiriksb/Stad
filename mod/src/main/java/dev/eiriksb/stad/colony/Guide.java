package dev.eiriksb.stad.colony;

import dev.eiriksb.stad.building.BuildingDef;
import dev.eiriksb.stad.building.ColonyStorage;
import dev.eiriksb.stad.building.Construction;
import dev.eiriksb.stad.building.Fields;
import dev.eiriksb.stad.building.Mine;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What the town hall tells a player about how the town works: what each job does and needs (and whether this town has
 * it), and the next steps in getting a town going. Server thread.
 */
public final class Guide {
    private Guide() {}

    /** A job: what it does, what it needs, what's missing for it in this town (null: nothing), and who has it. */
    public record Job(String job, String does, String needs, String problem, List<String> workers) {}

    /** A step in getting a town going, and whether it's done. */
    public record Step(String text, boolean done) {}

    private static final Map<String, String[]> JOBS = Map.ofEntries(
            Map.entry(Jobs.BUILDER, new String[]{"Builds what you plan, block by block. Makes up the ground under it, and builds what it can"
                    + " while it waits for the rest.", "Something planned (Building Plans, or describe it to them), and its materials in the town hall."}),
            Map.entry(Jobs.FARMER, new String[]{"Tills the town's fields, digs their water, sows them and harvests and replants. With no field,"
                    + " lays out a 9 by 9 one; design bigger ones at the architect's table (Field).",
                    "Seeds in the town hall (or they pick them from the grass, slowly), and flat grass or dirt for a field."}),
            Map.entry(Jobs.LUMBERJACK, new String[]{"Fells the trees on the town's land, top down, and replants. Plants the groves you design at"
                    + " the architect's table (Grove), or a grove of their own when there are no trees.", "Trees on the town's land, or saplings in the town hall."}),
            Map.entry(Jobs.COURIER, new String[]{"Carries what the builders and the town hall need from the warehouse, and empties the"
                    + " workplaces' chests into it.", "A warehouse."}),
            Map.entry(Jobs.GUARD, new String[]{"Patrols the town, and fights monsters and raiders.", "A sword (and armour) in the town hall."}),
            Map.entry(Jobs.COOK, new String[]{"Bakes bread (three wheat to a loaf), cooks meat, fish and potatoes, and brings the food to the"
                    + " town hall. Cooked food fills far more than raw.", "A furnace, smoker or campfire on the town's land, and something to cook."}),
            Map.entry(Jobs.FISHER, new String[]{"Fishes from the shore.", "A pond or river on the town's land (eight blocks of open water or more)."}),
            Map.entry(Jobs.SHEPHERD, new String[]{"Brings in animals from nearby (into a pen, if you design one at the architect's table), shears"
                    + " the sheep, feeds the animals so they breed, and butchers the extras.", "Sheep, cows, pigs or chickens in or near town, and wheat, carrots or seeds to feed them."}),
            Map.entry(Jobs.MINER, new String[]{"Digs the town's mine deeper, layer by layer, for stone, coal and ore.", "A Mine (from the building plans)."}),
            Map.entry(Jobs.SMITH, new String[]{"Makes the tools the workers are short of, mends worn ones and smelts ore.",
                    "A smithy (anvil, furnace and chest), and cobblestone or iron, and planks."}),
            Map.entry(Jobs.HEALER, new String[]{"Treats the ill and the wounded.", "Nothing; an infirmary gives the ill a bed."}),
            Map.entry(Jobs.MERCHANT, new String[]{"Sells the town's spare goods to players and buys what it's short of, for emeralds.",
                    "Emeralds in the town hall; a market or a market square (a stall each) to stand at."}),
            Map.entry(Jobs.TEACHER, new String[]{"Teaches the town's children, who grow up quicker to learn.", "A school (with a lectern), and children."}));

    public static String does(String job) {
        return JOBS.getOrDefault(job, new String[]{"", ""})[0];
    }

    public static String needs(String job) {
        return JOBS.getOrDefault(job, new String[]{"", ""})[1];
    }

    /** Every job, with what's missing for it here. */
    public static List<Job> jobs(ServerLevel level, Colony colony) {
        List<Job> out = new ArrayList<>();
        for (String j : Jobs.all()) {
            List<String> who = colony.citizens().stream().filter(r -> j.equals(r.job)).map(r -> r.name.split(" ")[0]).toList();
            String wp = Workplaces.wanted(j);
            out.add(new Job(j, does(j), needs(j) + (wp != null ? " Works faster from " + wp + "." : ""), problem(level, colony, j), who));
        }
        return out;
    }

    /** What's missing in this town for a job to work, in a few words; null when it has what it needs. */
    public static String problem(ServerLevel level, Colony colony, String job) {
        List<Building> planned = colony.buildings().stream().filter(Building::needsBuilding).toList();
        return switch (job) {
            case Jobs.BUILDER -> {
                if (planned.isEmpty()) {
                    yield "Nothing planned";
                }
                Building waiting = planned.stream().filter(b -> b.status == Building.Status.WAITING && !b.missing.isEmpty()).findFirst().orElse(null);
                yield waiting == null ? null : "The " + waiting.name + " is waiting for " + Construction.describe(waiting.missing, 3);
            }
            case Jobs.LUMBERJACK -> WorkSites.trees(level, colony).isEmpty() && !has(level, colony, s -> s.is(ItemTags.SAPLINGS))
                    ? "No trees on the town's land, and no saplings" : null;
            case Jobs.COURIER -> ColonyStorage.warehouses(level, colony).isEmpty() ? "No warehouse" : null;
            case Jobs.GUARD -> has(level, colony, s -> s.getItem() instanceof net.minecraft.world.item.SwordItem)
                    || colony.citizens().stream().anyMatch(r -> Jobs.GUARD.equals(r.job)) ? null : "No sword in the town hall";
            case Jobs.COOK -> WorkSites.cooking(level, colony).isEmpty() ? "No furnace, smoker or campfire" : null;
            case Jobs.FISHER -> WorkSites.shore(level, colony).isEmpty() ? "No pond or river" : null;
            case Jobs.SHEPHERD -> level.getEntitiesOfClass(Animal.class, new AABB(colony.center).inflate(colony.radius + 48, 16, colony.radius + 48),
                    dev.eiriksb.stad.citizen.goal.ShepherdGoal::herd).isEmpty() ? "No farm animals in or near town" : null;
            case Jobs.MINER -> Mine.pits(colony).isEmpty() ? "No mine or quarry" : null;
            case Jobs.SMITH -> built(colony, BuildingDef.SMITHY) ? null : "No smithy";
            case Jobs.TEACHER -> !built(colony, BuildingDef.SCHOOL) ? "No school"
                    : colony.citizens().stream().noneMatch(r -> level.getEntity(r.uuid) instanceof dev.eiriksb.stad.citizen.CitizenEntity e && e.isBaby())
                    ? "No children" : null;
            case Jobs.MERCHANT -> has(level, colony, s -> s.is(Items.EMERALD)) ? null : "No emeralds in the town hall";
            case Jobs.FARMER -> has(level, colony, s -> s.is(Items.WHEAT_SEEDS) || s.is(Items.POTATO) || s.is(Items.CARROT) || s.is(Items.BEETROOT_SEEDS))
                    || !WorkSites.farmland(level, colony).isEmpty() ? null : "No seeds in the town hall (they'll look in the grass)";
            default -> null;
        };
    }

    private static boolean built(Colony colony, net.minecraft.resources.ResourceLocation type) {
        return colony.buildings().stream().anyMatch(b -> b.done() && b.is(type));
    }

    private static boolean has(ServerLevel level, Colony colony, java.util.function.Predicate<net.minecraft.world.item.ItemStack> what) {
        return ColonyStorage.forBuilding(level, colony).count(what) > 0;
    }

    /** The steps in getting a town going, in order. */
    public static List<Step> steps(ServerLevel level, Colony colony) {
        List<Step> out = new ArrayList<>();
        List<CitizenRecord> people = List.copyOf(colony.citizens());
        List<Building> buildings = colony.buildings().stream().filter(b -> !Fields.is(b) && !b.isRepair()).toList();
        out.add(new Step("Place a bed or two on the town's land: newcomers move in while there's a free bed.", !people.isEmpty()));
        out.add(new Step("Give a citizen a job: right-click them. Start with a builder.", people.stream().anyMatch(r -> r.job != null)));
        out.add(new Step("Plan a house: craft Building Plans (two paper and a coal) and right-click the ground, or tell your builder what"
                + " you'd like.", !buildings.isEmpty()));
        Building waiting = buildings.stream().filter(b -> b.status == Building.Status.WAITING && !b.missing.isEmpty()).findFirst().orElse(null);
        out.add(new Step("Put the materials in the town hall (Storage)" + (waiting != null ? ": the " + waiting.name + " needs "
                + Construction.describe(waiting.missing, 3) : "") + ".", buildings.stream().anyMatch(Building::done) && waiting == null));
        out.add(new Step("Feed the town: put food in the town hall, and make a farmer and a cook (with a furnace) to keep it fed.",
                people.stream().anyMatch(r -> Jobs.FARMER.equals(r.job)) && people.stream().anyMatch(r -> Jobs.COOK.equals(r.job))));
        out.add(new Step("Give everyone a bed: plan houses as the town grows.", people.size() >= 3 && people.stream().allMatch(r -> r.home != null)));
        out.add(new Step("Design fields, a pen for the animals and a grove at the architect's table (a cartography table with Building Plans"
                + " on top): pick Field, Pen or Grove as its kind.", colony.buildings().stream().anyMatch(b -> !b.key.equals(Fields.KEY)
                && dev.eiriksb.stad.architect.Yard.is(b.type()))));
        out.add(new Step("Build workplaces (a farmhouse, lumber camp, kitchen...): workers there work faster and are happier.",
                people.stream().anyMatch(r -> r.workplace != null) && people.stream().filter(r -> Workplaces.wanted(r.job) != null)
                        .allMatch(r -> Workplaces.of(colony, r).isPresent())));
        out.add(new Step("Build a warehouse and make a courier: workers bring their goods there, and the courier carries what's needed.",
                built(colony, BuildingDef.WAREHOUSE) && people.stream().anyMatch(r -> Jobs.COURIER.equals(r.job))));
        out.add(new Step("Keep the town safe: make a guard and put a sword in the town hall.", people.stream().anyMatch(r -> Jobs.GUARD.equals(r.job))));
        return out;
    }
}
