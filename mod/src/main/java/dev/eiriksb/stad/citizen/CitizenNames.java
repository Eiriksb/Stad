package dev.eiriksb.stad.citizen;

import net.minecraft.util.RandomSource;

/** Names for new citizens, with a Nordic flavour. */
public final class CitizenNames {
    private static final String[] WOMEN = {
            "Astrid", "Ingrid", "Sigrid", "Solveig", "Ragnhild", "Liv", "Kari", "Turid", "Hedda", "Ylva", "Frida", "Gudrun",
            "Maren", "Signe", "Tove", "Eira", "Runa", "Aud", "Brynhild", "Dagny", "Elin", "Gro", "Helga", "Inga", "Jorunn",
            "Lene", "Marit", "Nora", "Oda", "Randi", "Siri", "Thora", "Unni", "Vilde", "Åse", "Embla", "Hild", "Rikke"};
    private static final String[] MEN = {
            "Bjørn", "Leif", "Ivar", "Gunnar", "Sigurd", "Torstein", "Halvard", "Erling", "Arne", "Einar", "Finn", "Geir",
            "Håkon", "Ingvar", "Jarle", "Knut", "Magnus", "Njål", "Odd", "Ragnar", "Sverre", "Trygve", "Ulf", "Vidar",
            "Asbjørn", "Eirik", "Frode", "Harald", "Kjetil", "Olav", "Rune", "Steinar", "Torvald", "Audun", "Bård", "Ketil"};
    private static final String[] PLACES = {
            "Berg", "Dal", "Haug", "Lund", "Moen", "Nes", "Rud", "Strand", "Vik", "Aas", "Bakken", "Fjeld", "Holm", "Li",
            "Myr", "Sand", "Stein", "Tveit", "Våge", "Øvre", "Elvestad", "Furuli", "Granheim", "Hammer", "Kvam", "Lien",
            "Nordby", "Solberg", "Sætre", "Tangen"};

    private CitizenNames() {}

    public static String random(boolean female, RandomSource r) {
        String first = female ? WOMEN[r.nextInt(WOMEN.length)] : MEN[r.nextInt(MEN.length)];
        return first + " " + PLACES[r.nextInt(PLACES.length)];
    }
}
