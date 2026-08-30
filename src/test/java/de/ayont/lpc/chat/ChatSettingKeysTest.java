package de.ayont.lpc.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Parsing for the values typed at {@code /showchatfrom} and {@code /allowmentions}. */
class ChatSettingKeysTest {

    @Test
    @DisplayName("showchatfrom accepts its own wording, in any case, with padding")
    void chatVisibility_parsesOwnKeys() {
        assertSame(ChatVisibility.EVERYONE, ChatVisibility.fromKey("everyone"));
        assertSame(ChatVisibility.FRIENDS, ChatVisibility.fromKey("  FRIENDS "));
        assertSame(ChatVisibility.NONE, ChatVisibility.fromKey("None"));
    }

    @Test
    @DisplayName("showchatfrom also accepts the wording allowmentions uses")
    void chatVisibility_parsesAliases() {
        assertSame(ChatVisibility.EVERYONE, ChatVisibility.fromKey("all"));
        assertSame(ChatVisibility.NONE, ChatVisibility.fromKey("nobody"));
    }

    @Test
    @DisplayName("allowmentions accepts its own wording and showchatfrom's")
    void mentionPolicy_parsesKeysAndAliases() {
        assertSame(MentionPolicy.ALL, MentionPolicy.fromKey("all"));
        assertSame(MentionPolicy.FRIENDS, MentionPolicy.fromKey("FRIENDS"));
        assertSame(MentionPolicy.NOBODY, MentionPolicy.fromKey("nobody"));
        assertSame(MentionPolicy.ALL, MentionPolicy.fromKey("everyone"));
        assertSame(MentionPolicy.NOBODY, MentionPolicy.fromKey("none"));
    }

    @Test
    @DisplayName("anything else is rejected rather than guessed at")
    void unknownValues_areNull() {
        assertNull(ChatVisibility.fromKey("banana"));
        assertNull(ChatVisibility.fromKey(""));
        assertNull(ChatVisibility.fromKey(null));
        assertNull(MentionPolicy.fromKey("sometimes"));
        assertNull(MentionPolicy.fromKey(null));
    }

    @Test
    @DisplayName("keys round-trip, so what is stored can be read back")
    void keys_roundTrip() {
        for (ChatVisibility mode : ChatVisibility.values()) {
            assertSame(mode, ChatVisibility.fromKey(mode.key()));
        }
        for (MentionPolicy policy : MentionPolicy.values()) {
            assertSame(policy, MentionPolicy.fromKey(policy.key()));
        }
        assertEquals("everyone", ChatVisibility.EVERYONE.key());
        assertEquals("all", MentionPolicy.ALL.key());
    }
}
