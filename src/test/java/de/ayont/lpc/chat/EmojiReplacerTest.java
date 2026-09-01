package de.ayont.lpc.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EmojiReplacerTest {

    @Test
    @DisplayName("replaces configured shortcuts with their values")
    void replace_substitutesShortcuts() {
        Component out = EmojiReplacer.replace(Component.text("hello :heart:"), Map.of(":heart:", "❤"));
        String serialized = MiniMessage.miniMessage().serialize(out);
        assertTrue(serialized.contains("❤"), "emoji should be inserted: " + serialized);
        assertFalse(serialized.contains(":heart:"), "shortcut should be gone: " + serialized);
    }

    @Test
    @DisplayName("leaves text without shortcuts unchanged")
    void replace_noShortcut_unchanged() {
        Component out = EmojiReplacer.replace(Component.text("plain text"), Map.of(":heart:", "❤"));
        assertTrue(MiniMessage.miniMessage().serialize(out).contains("plain text"));
    }

    @Test
    @DisplayName("hover template names the shortcut the glyph replaced")
    void replace_withHover_showsShortcut() {
        Component out = EmojiReplacer.replace(
                Component.text("hello :heart:"), Map.of(":heart:", "❤"), "<gray><code>");

        Component glyph = findGlyph(out, "❤");
        assertNotNull(glyph, "the glyph should be present: " + out);
        assertNotNull(glyph.hoverEvent(), "the glyph should carry a hover");

        String hover = PlainTextComponentSerializer.plainText()
                .serialize((Component) glyph.hoverEvent().value());
        assertEquals(":heart:", hover, "hover should name the shortcut");
    }

    @Test
    @DisplayName("empty hover template leaves the glyph without a hover")
    void replace_withoutHover_hasNoHoverEvent() {
        Component out = EmojiReplacer.replace(Component.text("hi :heart:"), Map.of(":heart:", "❤"), "");
        Component glyph = findGlyph(out, "❤");
        assertNotNull(glyph, "the glyph should still be inserted");
        assertNull(glyph.hoverEvent(), "no hover was configured, so none should be attached");
    }

    @Test
    @DisplayName("the two-argument form still attaches no hover")
    void replace_legacyOverload_hasNoHoverEvent() {
        Component out = EmojiReplacer.replace(Component.text("hi :heart:"), Map.of(":heart:", "❤"));
        assertNull(findGlyph(out, "❤").hoverEvent());
    }

    /** Finds the component holding exactly the given text, anywhere in the tree. */
    private static Component findGlyph(Component root, String text) {
        if (root instanceof TextComponent textComponent && text.equals(textComponent.content())) {
            return root;
        }
        for (Component child : root.children()) {
            Component found = findGlyph(child, text);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
