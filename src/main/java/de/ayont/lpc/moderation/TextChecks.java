package de.ayont.lpc.moderation;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Pure (Bukkit-free) text analysis used by {@link ModerationService}. Every method operates on raw
 * plaintext only and has no side effects, so the moderation rules are fully unit-testable and can
 * run safely on the async chat thread. All patterns here are constant (never built from player
 * input) to avoid ReDoS.
 */
public final class TextChecks {

    private static final Pattern URL = Pattern.compile(
            "(?i)(?:https?://)?(?:[a-z0-9-]+\\.)+[a-z]{2,}(?:/\\S*)?");
    private static final Pattern IP = Pattern.compile(
            "(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d{1,5})?");

    /** Legacy colour/format codes ({@code &c}, {@code §l}); players use them to split a word in two. */
    private static final Pattern LEGACY_CODES = Pattern.compile("(?i)[&\u00a7][0-9a-fk-or]");
    /** Four or more of the same character in a row; keeps real double letters ("ass", "shit"). */
    private static final Pattern LONG_RUN = Pattern.compile("(.)\\1{3,}", Pattern.DOTALL);
    /** Zero-width, bidi and other invisible formatting characters, plus control characters. */
    private static final Pattern INVISIBLES = Pattern.compile("[\\p{Cf}\\p{Cc}]");

    /** Regexes are matched in windows of this size so one huge message cannot make a match slow. */
    private static final int REGEX_WINDOW = 512;
    private static final int REGEX_OVERLAP = 64;

    private TextChecks() {
    }

    /** @return the fraction (0..1) of letters that are uppercase; 0 when there are no letters. */
    public static double uppercaseRatio(String text) {
        int letters = 0;
        int upper = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetter(c)) {
                letters++;
                if (Character.isUpperCase(c)) {
                    upper++;
                }
            }
        }
        return letters == 0 ? 0.0 : (double) upper / letters;
    }

    /** @return true if the text is long enough and its uppercase ratio exceeds the threshold. */
    public static boolean isShout(String text, int minLength, double maxRatio) {
        return text.length() >= minLength && uppercaseRatio(text) > maxRatio;
    }

    /**
     * Normalises text for fuzzy matching: lowercases, maps common leetspeak to letters, strips
     * non-alphanumerics, and collapses runs of the same character. Used for both profanity detection
     * and repeat detection so {@code "F U  C K!!"} and {@code "fuuuck"} normalise alike.
     */
    public static String normalize(String input) {
        String lower = input.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(lower.length());
        char previous = 0;
        for (int i = 0; i < lower.length(); i++) {
            char mapped = switch (lower.charAt(i)) {
                case '0' -> 'o';
                case '1' -> 'i';
                case '3' -> 'e';
                case '4', '@' -> 'a';
                case '5', '$' -> 's';
                case '7' -> 't';
                default -> lower.charAt(i);
            };
            if (Character.isLetterOrDigit(mapped) && mapped != previous) {
                sb.append(mapped);
                previous = mapped;
            } else if (!Character.isLetterOrDigit(mapped)) {
                previous = 0;
            }
        }
        return sb.toString();
    }

    /** @return true if the normalised message contains any of the (already-normalised) words. */
    public static boolean containsAny(String normalizedMessage, Collection<String> normalizedWords) {
        for (String word : normalizedWords) {
            if (!word.isEmpty() && normalizedMessage.contains(word)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Masks each occurrence of a blocked word in the original text with {@code maskChar} repeated to
     * the word's length. Matching is case-insensitive on the literal word.
     */
    public static String maskWords(String original, Collection<String> words, char maskChar) {
        String result = original;
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            String mask = String.valueOf(maskChar).repeat(word.length());
            result = Pattern.compile(Pattern.quote(word), Pattern.CASE_INSENSITIVE)
                    .matcher(result)
                    .replaceAll(Matcher.quoteReplacement(mask));
        }
        return result;
    }

    /** @return true if the text contains a non-allowlisted URL (or IP, when {@code detectIp}). */
    public static boolean containsAdvert(String text, boolean detectIp, Collection<String> allowlist) {
        Matcher urlMatcher = URL.matcher(text);
        while (urlMatcher.find()) {
            if (!isAllowlisted(urlMatcher.group(), allowlist)) {
                return true;
            }
        }
        if (detectIp) {
            Matcher ipMatcher = IP.matcher(text);
            while (ipMatcher.find()) {
                if (!isAllowlisted(ipMatcher.group(), allowlist)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Replaces every non-allowlisted URL/IP with {@code replacement}. */
    public static String maskAdvert(String text, boolean detectIp, Collection<String> allowlist, String replacement) {
        String result = replaceMatches(URL.matcher(text), allowlist, replacement);
        if (detectIp) {
            result = replaceMatches(IP.matcher(result), allowlist, replacement);
        }
        return result;
    }

    private static String replaceMatches(Matcher matcher, Collection<String> allowlist, String replacement) {
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String match = matcher.group();
            matcher.appendReplacement(sb, isAllowlisted(match, allowlist)
                    ? Matcher.quoteReplacement(match)
                    : Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static boolean isAllowlisted(String match, Collection<String> allowlist) {
        String lower = match.toLowerCase(Locale.ROOT);
        for (String allowed : allowlist) {
            if (!allowed.isEmpty() && lower.contains(allowed.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Finds the first character a whitelist refuses.
     *
     * <p>Servers usually want this to stop players pasting symbols the vanilla font cannot draw —
     * box-drawing characters, combining marks stacked into "zalgo" text, or right-to-left overrides
     * used to scramble a line. Rather than trying to enumerate those, the rule is written the other
     * way round: {@code allowed} matches the characters that ARE permitted, and anything outside it
     * is refused.</p>
     *
     * <p>Iteration is by code point, so a character outside the basic plane counts once and a
     * surrogate half is never tested on its own.</p>
     *
     * @return the offending code point, or {@code -1} when every character is allowed
     */
    public static int firstDisallowed(String text, Pattern allowed) {
        if (text == null || allowed == null) {
            return -1;
        }
        int index = 0;
        while (index < text.length()) {
            int codePoint = text.codePointAt(index);
            if (!allowed.matcher(new String(Character.toChars(codePoint))).matches()) {
                return codePoint;
            }
            index += Character.charCount(codePoint);
        }
        return -1;
    }

    /** Removes every character the whitelist refuses, leaving the rest untouched. */
    public static String stripDisallowed(String text, Pattern allowed) {
        if (text == null || allowed == null) {
            return text;
        }
        StringBuilder kept = new StringBuilder(text.length());
        int index = 0;
        while (index < text.length()) {
            int codePoint = text.codePointAt(index);
            String character = new String(Character.toChars(codePoint));
            if (allowed.matcher(character).matches()) {
                kept.append(character);
            }
            index += Character.charCount(codePoint);
        }
        return kept.toString();
    }

    // ------------------------------------------------------------------------------------------
    //  Regex word filter
    // ------------------------------------------------------------------------------------------

    /**
     * Compiles admin-written regular expressions for the profanity filter, case-insensitively and
     * Unicode-aware. Bad entries are reported through {@code onInvalid(source, reason)} and skipped, so
     * one typo can never take the whole filter (or the plugin) down. An expression that matches the
     * empty string is rejected too: it would "match" every message and silence the whole chat.
     */
    public static List<Pattern> compileRegexes(Collection<String> sources, BiConsumer<String, String> onInvalid) {
        List<Pattern> compiled = new ArrayList<>();
        for (String source : sources) {
            if (source == null || source.isBlank()) {
                continue;
            }
            try {
                Pattern pattern = Pattern.compile(source, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
                if (pattern.matcher("").find()) {
                    onInvalid.accept(source, "it matches the empty string, which would block every message");
                    continue;
                }
                compiled.add(pattern);
            } catch (PatternSyntaxException invalid) {
                onInvalid.accept(source, invalid.getDescription());
            } catch (StackOverflowError tooComplex) {
                onInvalid.accept(source, "the expression is too complex");
            }
        }
        return List.copyOf(compiled);
    }

    /**
     * Cleans a message for regex detection only (never for display): drops colour codes, invisible
     * characters and control characters, folds fullwidth / stylised letters to plain ones (NFKC), and
     * squeezes any run of four or more identical characters down to two so "fuuuuuuuuuck" and
     * thousand-letter spam are cheap to match.
     */
    public static String cleanForRegex(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        String text = LEGACY_CODES.matcher(input).replaceAll("");
        text = Normalizer.normalize(text, Normalizer.Form.NFKC);
        text = INVISIBLES.matcher(text).replaceAll("");
        return LONG_RUN.matcher(text).replaceAll("$1$1");
    }

    /** @return the first match of any pattern in {@code text}, or {@code null} when none matches. */
    public static String firstRegexMatch(String text, List<Pattern> patterns) {
        if (text == null || text.isEmpty() || patterns.isEmpty()) {
            return null;
        }
        for (int start = 0; ; start += REGEX_WINDOW - REGEX_OVERLAP) {
            int end = Math.min(text.length(), start + REGEX_WINDOW);
            CharSequence window = text.subSequence(start, end);
            for (Pattern pattern : patterns) {
                Matcher matcher = pattern.matcher(window);
                if (matcher.find() && matcher.end() > matcher.start()) {
                    return matcher.group();
                }
            }
            if (end >= text.length()) {
                return null;
            }
        }
    }

    /** @return true if any pattern matches {@code text}. */
    public static boolean matchesAnyRegex(String text, List<Pattern> patterns) {
        return firstRegexMatch(text, patterns) != null;
    }

    /** Replaces every regex match in the original text with {@code maskChar} repeated to its length. */
    public static String maskRegexMatches(String original, List<Pattern> patterns, char maskChar) {
        String result = original;
        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(result);
            StringBuilder sb = new StringBuilder();
            while (matcher.find()) {
                int length = matcher.end() - matcher.start();
                matcher.appendReplacement(sb, length == 0
                        ? ""
                        : Matcher.quoteReplacement(String.valueOf(maskChar).repeat(length)));
            }
            matcher.appendTail(sb);
            result = sb.toString();
        }
        return result;
    }
}
