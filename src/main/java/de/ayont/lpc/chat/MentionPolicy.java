package de.ayont.lpc.chat;

import java.util.Locale;

/**
 * Who may ping a player by name, chosen with {@code /allowmentions}.
 *
 * <p>The setting belongs to the player being mentioned: it decides who is allowed to make their
 * client ping them by typing their name.</p>
 */
public enum MentionPolicy {

    /** Anyone whose message they can see — the default. */
    ALL("all", "everyone"),
    /** Only players they are friends with in FriendSystem. */
    FRIENDS("friends"),
    /** Nobody; their name never pings them. */
    NOBODY("nobody", "none");

    private final String key;
    private final String[] aliases;

    MentionPolicy(String key, String... aliases) {
        this.key = key;
        this.aliases = aliases;
    }

    /** The value as typed on the command line and stored in {@code chat-settings.yml}. */
    public String key() {
        return key;
    }

    /**
     * Parses a typed or stored value, or {@code null} when it names no policy.
     *
     * <p>The wording {@code /showchatfrom} uses is accepted too.</p>
     */
    public static MentionPolicy fromKey(String key) {
        if (key == null) {
            return null;
        }
        String normalised = key.trim().toLowerCase(Locale.ROOT);
        for (MentionPolicy policy : values()) {
            if (policy.key.equals(normalised)) {
                return policy;
            }
            for (String alias : policy.aliases) {
                if (alias.equals(normalised)) {
                    return policy;
                }
            }
        }
        return null;
    }
}
