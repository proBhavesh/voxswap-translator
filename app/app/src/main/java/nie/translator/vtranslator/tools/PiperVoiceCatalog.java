package nie.translator.vtranslator.tools;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import nie.translator.vtranslator.Global;

/**
 * Catalog of Piper TTS voices: which model file each language uses, which languages
 * have male+female variants, and on-disk availability checks. Owns a small per-(lang,gender)
 * resolution cache so per-utterance lookups don't hit the filesystem.
 */
public final class PiperVoiceCatalog {

    public static final class Variants {
        @NonNull public final String male;
        @NonNull public final String female;

        public Variants(@NonNull String male, @NonNull String female) {
            this.male = male;
            this.female = female;
        }

        @NonNull
        public String forGender(Global.Gender gender) {
            return gender == Global.Gender.MALE ? male : female;
        }
    }

    private static final Map<String, Variants> VARIANTS;
    private static final Map<String, String> SINGLE;

    static {
        Map<String, Variants> v = new HashMap<>();
        v.put("en", new Variants("en_US-ryan-medium.onnx",        "en_US-lessac-medium.onnx"));
        v.put("fr", new Variants("fr_FR-tom-medium.onnx",         "fr_FR-siwis-medium.onnx"));
        v.put("hi", new Variants("hi_IN-rohan-medium.onnx",       "hi_IN-priyamvada-medium.onnx"));
        v.put("hu", new Variants("hu_HU-imre-medium.onnx",        "hu_HU-anna-medium.onnx"));
        v.put("is", new Variants("is_IS-bui-medium.onnx",         "is_IS-salka-medium.onnx"));
        v.put("ml", new Variants("ml_IN-arjun-medium.onnx",       "ml_IN-meera-medium.onnx"));
        v.put("pl", new Variants("pl_PL-darkman-medium.onnx",     "pl_PL-gosia-medium.onnx"));
        v.put("ru", new Variants("ru_RU-denis-medium.onnx",       "ru_RU-irina-medium.onnx"));
        VARIANTS = Collections.unmodifiableMap(v);

        Map<String, String> s = new HashMap<>();
        s.put("ar", "ar_JO-kareem-medium.onnx");
        s.put("ca", "ca_ES-upc_ona-medium.onnx");
        s.put("cs", "cs_CZ-jirka-medium.onnx");
        s.put("da", "da_DK-talesyntese-medium.onnx");
        s.put("de", "de_DE-thorsten-medium.onnx");
        s.put("el", "el_GR-rapunzelina-low.onnx");
        s.put("es", "es_ES-davefx-medium.onnx");
        s.put("fa", "fa_IR-gyro-medium.onnx");
        s.put("fi", "fi_FI-harri-medium.onnx");
        s.put("id", "id_ID-news_tts-medium.onnx");
        s.put("it", "it_IT-paola-medium.onnx");
        s.put("ka", "ka_GE-natia-medium.onnx");
        s.put("kk", "kk_KZ-issai-high.onnx");
        s.put("lv", "lv_LV-aivars-medium.onnx");
        s.put("ne", "ne_NP-google-medium.onnx");
        s.put("nl", "nl_NL-miro-high.onnx");
        s.put("no", "no_NO-talesyntese-medium.onnx");
        s.put("pt", "pt_BR-faber-medium.onnx");
        s.put("sk", "sk_SK-lili-medium.onnx");
        s.put("sr", "sr_RS-serbski_institut-medium.onnx");
        s.put("sv", "sv_SE-nst-medium.onnx");
        s.put("sw", "sw_CD-lanfrica-medium.onnx");
        s.put("tr", "tr_TR-fahrettin-medium.onnx");
        s.put("uk", "uk_UA-ukrainian_tts-medium.onnx");
        s.put("vi", "vi_VN-vais1000-medium.onnx");
        SINGLE = Collections.unmodifiableMap(s);
    }

    /* ConcurrentHashMap rejects null values, so use NONE as a "not downloaded" sentinel. */
    private static final ConcurrentHashMap<String, String> RESOLUTION_CACHE = new ConcurrentHashMap<>();
    private static final String NONE = "";

    private PiperVoiceCatalog() {}

    public static boolean voiceFileExists(Context context, @Nullable String filename) {
        return filename != null && new File(context.getFilesDir(), filename).exists();
    }

    @Nullable
    public static Variants getVariants(String lang) {
        return VARIANTS.get(lang);
    }

    /** Returns one file for single-voice languages, two (male + female) for variant languages. */
    public static List<String> requiredFiles(String lang) {
        List<String> files = new ArrayList<>();
        Variants v = VARIANTS.get(lang);
        if (v != null) {
            files.add(v.male);
            files.add(v.female);
        } else {
            String single = SINGLE.get(lang);
            if (single != null) files.add(single);
        }
        return files;
    }

    /** True if at least one voice for the language is on disk — used by the language picker. */
    public static boolean hasAnyDownloaded(Context ctx, String lang) {
        for (String file : requiredFiles(lang)) {
            if (voiceFileExists(ctx, file)) return true;
        }
        return false;
    }

    public static boolean isAvailable(String lang) {
        return SINGLE.containsKey(lang) || VARIANTS.containsKey(lang);
    }

    public static boolean isVariant(String lang) {
        return VARIANTS.containsKey(lang);
    }

    /**
     * Prefers the requested gender; falls back to the opposite variant; returns null if neither
     * is on disk. Result is memoized — call {@link #invalidate(String)} after a download completes.
     */
    @Nullable
    public static String resolveAvailableVoice(Context ctx, String lang, Global.Gender gender) {
        String key = lang + ":" + gender.ordinal();
        String cached = RESOLUTION_CACHE.get(key);
        if (cached != null) return cached.equals(NONE) ? null : cached;

        Variants v = VARIANTS.get(lang);
        String result;
        if (v != null) {
            String preferred = v.forGender(gender);
            if (voiceFileExists(ctx, preferred)) {
                result = preferred;
            } else {
                Global.Gender opposite = (gender == Global.Gender.MALE) ? Global.Gender.FEMALE : Global.Gender.MALE;
                String alt = v.forGender(opposite);
                result = voiceFileExists(ctx, alt) ? alt : null;
            }
        } else {
            String single = SINGLE.get(lang);
            result = voiceFileExists(ctx, single) ? single : null;
        }

        RESOLUTION_CACHE.put(key, result == null ? NONE : result);
        return result;
    }

    /** Drops cached resolution entries for one language. Call after a download completes. */
    public static void invalidate(String lang) {
        RESOLUTION_CACHE.remove(lang + ":" + Global.Gender.MALE.ordinal());
        RESOLUTION_CACHE.remove(lang + ":" + Global.Gender.FEMALE.ordinal());
    }

    /** Drops all cached resolution entries. Call when target languages change. */
    public static void invalidateAll() {
        RESOLUTION_CACHE.clear();
    }
}
