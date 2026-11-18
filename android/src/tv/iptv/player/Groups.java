package tv.iptv.player;

import java.util.HashMap;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Many providers tag their categories: live ones start with a region ("|EU| FRANCE SPORTS"), movies and series end
 * with a language ("VOD - ACTION [FR]"). Reading those tags lets the app show only what the household watches.
 */
final class Groups {
    private static final Pattern REGION = Pattern.compile("^\\s*\\|\\s*([^|]{2,6}?)\\s*\\|");
    private static final Pattern LANGUAGE = Pattern.compile("\\[([^\\]]{2,10})\\]\\s*$");
    private static final Pattern ADULT = Pattern.compile("(?i)(adult|xxx|\\+\\s*18|18\\s*\\+|porn)");
    private static final Pattern DECOR = Pattern.compile("^(VOD|SRS)\\s*-\\s*");
    private static final HashMap<String, String> LANG = new HashMap<>(), LIVE = new HashMap<>();

    static {
        String[] lang = {"FR", "French", "EN", "English", "ES", "Spanish", "DE", "German", "IT", "Italian", "NL", "Dutch",
                "PT", "Portuguese", "PL", "Polish", "GR", "Greek", "AR", "Arabic", "TR", "Turkish", "RU", "Russian",
                "AL", "Albanian", "RO", "Romanian", "BG", "Bulgarian", "HU", "Hungarian", "DK", "Danish", "NO", "Norwegian",
                "FI", "Finnish", "IS", "Icelandic", "IR", "Persian", "AF", "African", "EXYU", "Ex-Yugoslavia",
                "MULTISUB", "Multi-language", "MU", "Scandinavian", "QC", "Québécois", "SO", "Somali"};
        for (int i = 0; i < lang.length; i += 2) LANG.put(lang[i], lang[i + 1]);
        String[] live = {"EU", "Europe", "FR", "France extras", "AR", "Arabic", "ES", "Spain", "IT", "Italy", "NL", "Netherlands",
                "DE", "Germany", "PT", "Portugal", "PL", "Poland", "GR", "Greece", "ALB", "Albania", "BLN", "Balkans",
                "AS", "Asia", "MT", "Malta", "UK", "United Kingdom", "EN", "English 24/7", "IE", "Ireland",
                "NA", "North America", "AM", "Americas", "AU", "Australia", "NZ", "New Zealand",
                "SA", "Caribbean & Latin America", "AF", "Africa", "TH", "Thailand", "AZ", "Azerbaijan"};
        for (int i = 0; i < live.length; i += 2) LIVE.put(live[i], live[i + 1]);
    }

    private Groups() {}

    static boolean adult(String category) { return category != null && ADULT.matcher(category).find(); }

    /** The region (live) or language (movies, series) code of a category, or "" when it carries none. */
    static String of(int kind, String category) {
        if (category == null) return "";
        if (kind == App.LIVE) {
            Matcher m = REGION.matcher(category);
            return m.find() ? m.group(1).trim().toUpperCase(Locale.ROOT) : "";
        }
        Matcher m = LANGUAGE.matcher(category);
        if (m.find()) {
            String code = m.group(1).trim().toUpperCase(Locale.ROOT);
            return "ALB".equals(code) ? "AL" : code;
        }
        for (int i = 0; i < category.length(); i++) {
            char c = category.charAt(i);
            if (c >= 0x0600 && c <= 0x06FF) return "AR";
        }
        return "";
    }

    static String label(String code, boolean live) {
        if (code.isEmpty()) return live ? "International & 4K" : "Untagged";
        String name = (live ? LIVE : LANG).get(code);
        return name == null ? code : name;
    }

    /** Category name without the provider's bookkeeping: "VOD - ACTION [FR]" becomes "Action". */
    static String clean(int kind, String category) {
        if (category == null) return "";
        String s = category.trim();
        if (kind == App.LIVE) {
            Matcher m = REGION.matcher(s);
            if (m.find()) s = s.substring(m.end()).trim();
            return s.isEmpty() ? category : s;
        }
        s = DECOR.matcher(s).replaceFirst("");
        return s.isEmpty() ? category : s;
    }
}

/** Movie and series names arrive as "FR - Heart of the Beast (2026)"; split the tag from the title. */
final class Names {
    private static final Pattern TAGGED = Pattern.compile("^\\s*([A-Z0-9]{2,4})\\s*[-|:]\\s+(.+)$");

    private Names() {}

    static String tag(String name) {
        if (name == null) return "";
        Matcher m = TAGGED.matcher(name);
        return m.matches() ? m.group(1) : "";
    }

    static String title(String name) {
        if (name == null) return "";
        Matcher m = TAGGED.matcher(name);
        return m.matches() ? m.group(2).trim() : name.trim();
    }

    /** Providers insert pseudo-channels like "##### SPORTS FHD #####" as dividers. */
    static boolean divider(String name) {
        if (name == null || name.length() < 3) return false;
        char c = name.charAt(0);
        return (c == '#' || c == '=' || c == '*' || c == '~') && name.charAt(1) == c;
    }

    static String dividerText(String name) {
        return name == null ? "" : name.replaceAll("^[#=*~\\s]+|[#=*~\\s]+$", "");
    }
}
