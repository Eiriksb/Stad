package dev.eiriksb.stad.citizen;

import net.minecraft.util.RandomSource;

/** Names for new citizens, old English village names. */
public final class CitizenNames {
    private static final String[] WOMEN = {
            "Ada", "Agnes", "Alice", "Amice", "Annis", "Beatrice", "Bess", "Cecily", "Clemence", "Dora", "Edith", "Eleanor",
            "Elsie", "Emma", "Florence", "Grace", "Hazel", "Hilda", "Isabel", "Ivy", "Joan", "Juliana", "Lettice", "Lily",
            "Mabel", "Margery", "Matilda", "Maud", "Molly", "Nell", "Petra", "Rose", "Ruth", "Sybil", "Tilly", "Winifred"};
    private static final String[] MEN = {
            "Alfred", "Arthur", "Baldwin", "Bernard", "Cuthbert", "Edmund", "Edward", "Edwin", "Geoffrey", "George", "Gilbert",
            "Godfrey", "Harold", "Henry", "Hugh", "Jasper", "John", "Lambert", "Martin", "Miles", "Nicholas", "Oliver", "Osbert",
            "Peter", "Ralph", "Robert", "Roger", "Simon", "Stephen", "Thomas", "Tobias", "Walter", "Warin", "William", "Wilfred"};
    private static final String[] PLACES = {
            "Ashby", "Baker", "Brook", "Carter", "Cooper", "Dale", "Fairweather", "Field", "Fisher", "Fletcher", "Green", "Hale",
            "Hill", "Holt", "Lane", "Marsh", "Mason", "Meadows", "Miller", "Oakley", "Shaw", "Smith", "Stone", "Thatcher",
            "Turner", "Underwood", "Webb", "Wells", "Wood", "Wright"};

    private CitizenNames() {}

    public static String random(boolean female, RandomSource r) {
        String first = female ? WOMEN[r.nextInt(WOMEN.length)] : MEN[r.nextInt(MEN.length)];
        return first + " " + PLACES[r.nextInt(PLACES.length)];
    }
}
