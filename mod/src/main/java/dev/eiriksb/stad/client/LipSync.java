package dev.eiriksb.stad.client;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What shape each citizen's mouth makes, and when. Heard voices (Simple Voice Chat hands over every 20 ms of a
 * citizen's speech: {@link #heard}) are read for how loud they are and what kind of sound it is ({@link #shapeOf}):
 * silence closes the mouth, hiss shows the teeth, a hum presses the lips, and the vowels open it (ah), widen it (ee) or
 * round it (oh, oo). Without a voice (a line only written), the line's letters are mouthed at a talking pace instead
 * ({@link #said}), unless a voice turns up. Also how they feel, from the reply ({@link #feel}). Any thread.
 */
public final class LipSync {
    private LipSync() {}

    /** mouth shapes: indexes into {@link CitizenSkins#MOUTH_SHAPES} */
    public static final int REST = 0, SMILE = 1, FROWN = 2, SMALL = 3, OPEN = 4, WIDE = 5, ROUND = 6, PRESSED = 7, TEETH = 8, GRIN = 9, OO = 10;

    /** how long a heard frame takes to come out of the speakers after it's handed over (the playback buffer) */
    static final long DELAY_MS = 60;
    static final long FRAME_MS = 20;
    /** without a voice, a letter takes this long to say */
    static final long LETTER_MS = 65;
    /** how long a written line waits for its voice before it's mouthed from its letters */
    static final long WAIT_FOR_VOICE_MS = 1500;

    private record Shape(long at, int shape) {}

    private static final class Mouth {
        final ArrayDeque<Shape> shapes = new ArrayDeque<>();
        long lastHeard;
        /** when the last heard frame is due (frames come out one after another) */
        long nextHeard;
        int held, last = REST;
        /** the shape being made, since when (-1: none) */
        int current = -1;
        long currentAt;
    }

    private record Feeling(String emotion, long until) {}

    private static final Map<UUID, Mouth> mouths = new ConcurrentHashMap<>();
    private static final Map<UUID, Feeling> feelings = new ConcurrentHashMap<>();

    /** A citizen's voice: 20 ms of 48 kHz audio, as it's about to be played. */
    public static void heard(UUID citizen, short[] audio) {
        long now = System.currentTimeMillis();
        Mouth m = mouths.computeIfAbsent(citizen, k -> new Mouth());
        synchronized (m) {
            if (now - m.lastHeard > 400) {
                m.shapes.clear(); // a new stretch of speech: drop anything mouthed from its letters
                m.nextHeard = now + DELAY_MS;
            }
            m.lastHeard = now;
            long at = Math.max(now + DELAY_MS, m.nextHeard);
            m.nextHeard = at + FRAME_MS;
            int shape = shapeOf(audio);
            // hold a shape for two frames at least, so the mouth doesn't flicker
            if (shape != m.last && m.held < 2 && shape != REST) {
                shape = m.last;
            }
            m.held = shape == m.last ? m.held + 1 : 1;
            m.last = shape;
            m.shapes.add(new Shape(at, shape));
            while (m.shapes.size() > 200) {
                m.shapes.poll();
            }
        }
    }

    private static final int N = 1024;
    private static final double[] WINDOW = new double[960];

    static {
        for (int i = 0; i < WINDOW.length; i++) {
            WINDOW[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (WINDOW.length - 1));
        }
    }

    /**
     * The mouth shape for a frame of speech (48 kHz): how loud it is opens the mouth, and its spectrum says the shape.
     * Hiss (most of it above 3.5 kHz) shows the teeth; a hum (mostly below 350 Hz, quiet) presses the lips; a high
     * first formant (600 Hz and up) is an open vowel (ah); a strong second formant high up (1.6 kHz and up) a bright one
     * (ee); hardly any second formant a round one (oh, oo).
     */
    public static int shapeOf(short[] s) {
        if (s == null || s.length < 2) {
            return REST;
        }
        int n = Math.min(s.length, WINDOW.length);
        double sum = 0;
        double[] re = new double[N], im = new double[N];
        for (int i = 0; i < n; i++) {
            double v = s[i] / 32768.0;
            sum += v * v;
            re[i] = v * WINDOW[i];
        }
        double rms = Math.sqrt(sum / n);
        if (rms < 0.012) {
            return REST;
        }
        fft(re, im);
        double hz = 48000.0 / N;
        double total = 0, hiss = 0, low = 0, f1band = 0, f2band = 0, f1 = 0, f1max = -1, f2 = 0, f2max = -1;
        for (int k = 1; k < N / 2; k++) {
            double f = k * hz, p = re[k] * re[k] + im[k] * im[k];
            if (f < 80 || f >= 8000) {
                continue;
            }
            total += p;
            if (f >= 3500) {
                hiss += p;
            } else if (f < 350) {
                low += p;
            }
            if (f >= 300 && f < 1000) {
                f1band += p;
                if (p > f1max) {
                    f1max = p;
                    f1 = f;
                }
            } else if (f >= 1000 && f < 2800) {
                f2band += p;
                if (p > f2max) {
                    f2max = p;
                    f2 = f;
                }
            }
        }
        total += 1e-12;
        double open = (20 * Math.log10(rms) + 34) / 14; // 0 quiet, 1 loud
        double second = f2band / (f1band + f2band + 1e-12);
        if (hiss / total > 0.3 && open < 0.8 || hiss / total > 0.5) {
            return TEETH;
        }
        if (open < 0.25) {
            return low / total > 0.75 ? PRESSED : SMALL;
        }
        if (f1 >= 600) {
            return OPEN;
        }
        if (second > 0.22 && f2 > 1600) {
            return WIDE;
        }
        if (second < 0.07) {
            return open > 0.6 ? ROUND : OO;
        }
        return open > 0.6 ? OPEN : SMALL;
    }

    /** In place, radix 2 ({@code re.length} a power of two). */
    private static void fft(double[] re, double[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) {
                j ^= bit;
            }
            j ^= bit;
            if (i < j) {
                double t = re[i];
                re[i] = re[j];
                re[j] = t;
                t = im[i];
                im[i] = im[j];
                im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double a = -2 * Math.PI / len, wr = Math.cos(a), wi = Math.sin(a);
            for (int i = 0; i < n; i += len) {
                double cr = 1, ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int u = i + k, v = i + k + len / 2;
                    double tr = re[v] * cr - im[v] * ci, ti = re[v] * ci + im[v] * cr;
                    re[v] = re[u] - tr;
                    im[v] = im[u] - ti;
                    re[u] += tr;
                    im[u] += ti;
                    double nr = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = nr;
                }
            }
        }
    }

    /** A line a citizen said (or wrote): mouthed from its letters, unless their voice is heard saying it. */
    public static void said(UUID citizen, String text, boolean voices) {
        long now = System.currentTimeMillis();
        Mouth m = mouths.computeIfAbsent(citizen, k -> new Mouth());
        synchronized (m) {
            if (now - m.lastHeard < WAIT_FOR_VOICE_MS) {
                return; // being heard already
            }
            long at = now + (voices ? WAIT_FOR_VOICE_MS : 0);
            m.shapes.clear();
            for (char ch : text.toLowerCase().toCharArray()) {
                int shape = switch (ch) {
                    case 'a', 'æ', 'å' -> OPEN;
                    case 'e', 'i', 'y' -> WIDE;
                    case 'o', 'ø' -> ROUND;
                    case 'u', 'w', 'q' -> OO;
                    case 'm', 'b', 'p' -> PRESSED;
                    case 'f', 'v' -> TEETH;
                    case ' ', '\'', '-' -> REST;
                    case ',', '.', '!', '?', ';', ':' -> -1;
                    default -> Character.isLetter(ch) ? SMALL : REST;
                };
                if (shape == -1) { // a pause
                    m.shapes.add(new Shape(at, REST));
                    at += LETTER_MS * 4;
                    continue;
                }
                m.shapes.add(new Shape(at, shape));
                at += LETTER_MS;
            }
            m.shapes.add(new Shape(at, REST));
            m.nextHeard = 0;
        }
    }

    /** The shape a citizen's mouth makes now, or -1 when they aren't speaking. */
    public static int now(UUID citizen) {
        Mouth m = mouths.get(citizen);
        if (m == null) {
            return -1;
        }
        long now = System.currentTimeMillis();
        synchronized (m) {
            while (!m.shapes.isEmpty() && m.shapes.peek().at() <= now) {
                Shape s = m.shapes.poll();
                m.current = s.shape();
                m.currentAt = s.at();
            }
            if (m.current < 0) {
                return m.shapes.isEmpty() ? -1 : REST; // waiting for the first
            }
            if (m.shapes.isEmpty() && now - m.currentAt > 120) {
                m.current = -1; // nothing after it: done
                return -1;
            }
            return m.current;
        }
    }

    /** How a citizen feels about what they said ({@code emotion} from They Will Talk), for a while. */
    public static void feel(UUID citizen, String emotion, long forMs) {
        feelings.put(citizen, new Feeling(emotion == null ? "neutral" : emotion, System.currentTimeMillis() + forMs));
    }

    /** How they feel now ("neutral" when nothing in particular). */
    public static String feeling(UUID citizen) {
        Feeling f = feelings.get(citizen);
        if (f == null || f.until() < System.currentTimeMillis()) {
            if (f != null) {
                feelings.remove(citizen);
            }
            return "neutral";
        }
        return f.emotion();
    }

    public static void clear() {
        mouths.clear();
        feelings.clear();
    }
}
