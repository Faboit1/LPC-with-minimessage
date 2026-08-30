package de.ayont.lpc.moderation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class TextChecksTest {

    @Test
    @DisplayName("uppercaseRatio counts only letters")
    void uppercaseRatio_ignoresNonLetters() {
        assertEquals(1.0, TextChecks.uppercaseRatio("AB!! 12"));
        assertEquals(0.0, TextChecks.uppercaseRatio("12345"));
        assertEquals(0.5, TextChecks.uppercaseRatio("Ab"));
    }

    @Test
    @DisplayName("isShout requires both length and ratio")
    void isShout_lengthAndRatio() {
        assertTrue(TextChecks.isShout("STOP RIGHT THERE", 8, 0.6));
        assertFalse(TextChecks.isShout("HI", 8, 0.6));
        assertFalse(TextChecks.isShout("this is calm enough", 8, 0.6));
    }

    @Test
    @DisplayName("normalize folds leetspeak, case, spacing and repeats")
    void normalize_foldsObfuscation() {
        assertEquals("helo", TextChecks.normalize("Heeello"));
        assertEquals("helo", TextChecks.normalize("h3LL0"));
        assertEquals("youareanob", TextChecks.normalize("you are a n00b!"));
    }

    @Test
    @DisplayName("containsAny matches normalised blocked words")
    void containsAny_matchesNormalised() {
        List<String> words = List.of(TextChecks.normalize("noob"));
        assertTrue(TextChecks.containsAny(TextChecks.normalize("you n0ob"), words));
        assertFalse(TextChecks.containsAny(TextChecks.normalize("you are nice"), words));
    }

    @Test
    @DisplayName("maskWords replaces blocked words case-insensitively")
    void maskWords_replacesLiteral() {
        assertEquals("Hello ****", TextChecks.maskWords("Hello NOOB", List.of("noob"), '*'));
    }

    @Test
    @DisplayName("containsAdvert detects non-allowlisted URLs and respects allowlist")
    void containsAdvert_urlAndAllowlist() {
        assertTrue(TextChecks.containsAdvert("join evil.net now", false, List.of()));
        assertFalse(TextChecks.containsAdvert("see youtube.com/watch", false, List.of("youtube.com")));
        assertFalse(TextChecks.containsAdvert("no links here", false, List.of()));
    }

    @Test
    @DisplayName("containsAdvert optionally detects IPs")
    void containsAdvert_ip() {
        assertTrue(TextChecks.containsAdvert("connect 192.168.0.1:25565", true, List.of()));
        assertFalse(TextChecks.containsAdvert("connect 192.168.0.1", false, List.of()));
    }

    @Test
    @DisplayName("maskAdvert redacts non-allowlisted links only")
    void maskAdvert_redactsLinks() {
        assertEquals("join [link] now", TextChecks.maskAdvert("join evil.net now", false, List.of(), "[link]"));
        assertEquals("watch youtube.com today",
                TextChecks.maskAdvert("watch youtube.com today", false, List.of("youtube.com"), "[link]"));
    }

    private static final Pattern ASCII = Pattern.compile("[\\x20-\\x7E]");

    @Test
    @DisplayName("printable ASCII passes the whitelist")
    void firstDisallowed_ascii_allowed() {
        assertEquals(-1, TextChecks.firstDisallowed("Hello, world! 123 ~", ASCII));
    }

    @Test
    @DisplayName("emoji shortcuts survive an ASCII-only whitelist")
    void firstDisallowed_shortcut_allowed() {
        assertEquals(-1, TextChecks.firstDisallowed("i said :heart: to you", ASCII));
    }

    @Test
    @DisplayName("refuses accents, glyphs, zalgo marks and RTL overrides")
    void firstDisallowed_reportsOffendingCodePoint() {
        assertEquals(0x00E9, TextChecks.firstDisallowed("caf\u00E9", ASCII));
        assertEquals(0x2764, TextChecks.firstDisallowed("hi \u2764", ASCII));
        assertEquals(0x0301, TextChecks.firstDisallowed("z\u0301", ASCII));
        assertEquals(0x202E, TextChecks.firstDisallowed("abc\u202Edef", ASCII));
    }

    @Test
    @DisplayName("counts a character outside the basic plane once, not as two surrogates")
    void firstDisallowed_astralCodePoint() {
        assertEquals(0x1F600, TextChecks.firstDisallowed("hi \uD83D\uDE00", ASCII));
    }

    @Test
    @DisplayName("stripping keeps the allowed characters and drops the rest")
    void stripDisallowed_keepsAllowed() {
        // Stripping removes the character; it does not transliterate it to 'e'.
        assertEquals("caf", TextChecks.stripDisallowed("caf\u00E9", ASCII));
        assertEquals("hi ", TextChecks.stripDisallowed("hi \u2764", ASCII));
        assertEquals("Hello!", TextChecks.stripDisallowed("Hello!", ASCII));
    }
}
