package dev.eiriksb.stad.colony;

import net.minecraft.util.RandomSource;

import java.util.Collection;

/** Names for new towns, with a Nordic flavour. */
final class TownNames {
    private static final String[] FIRST = {
            "Elve", "Furu", "Bjørke", "Stein", "Fjell", "Sol", "Ny", "Gamle", "Lille", "Store", "Nord", "Sør", "Øst", "Vest",
            "Eike", "Rogn", "Lyng", "Myr", "Sand", "Strand", "Berg", "Ulv", "Ørne", "Revs", "Bjørne", "Hjorte", "Å", "Kilde"};
    private static final String[] LAST = {
            "bakken", "vik", "dal", "heim", "stad", "by", "lia", "nes", "haug", "vang", "mo", "ås", "rud", "set", "tun", "holm"};

    private TownNames() {}

    static String random(RandomSource r, Collection<String> taken) {
        for (int i = 0; i < 50; i++) {
            String n = FIRST[r.nextInt(FIRST.length)] + LAST[r.nextInt(LAST.length)];
            if (!taken.contains(n)) {
                return n;
            }
        }
        return "Town " + (taken.size() + 1);
    }
}
