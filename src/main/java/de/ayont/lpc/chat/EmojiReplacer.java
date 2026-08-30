package de.ayont.lpc.chat;

import de.ayont.lpc.LPC;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Replaces admin-configured text shortcuts (e.g. {@code :heart:}) in a player's already-safe
 * message component with literal replacement text/glyphs. Because the replacement table is entirely
 * server-defined and substitution runs via {@link Component#replaceText}, players can only trigger
 * keys the admin configured and can never inject MiniMessage tags or placeholders.
 *
 * <p>The glyph can carry a hover showing the shortcut it came from, so readers can see that
 * {@code ❤} was typed as {@code :heart:} rather than pasted. The hover is an operator-authored
 * MiniMessage template and the shortcut goes in through {@link Placeholder#unparsed}, so — as with
 * the replacement table itself — no player text is ever parsed as MiniMessage.</p>
 */
public final class EmojiReplacer {

    /** Shown on the glyph when the config does not say otherwise. */
    private static final String DEFAULT_HOVER_TEXT = "<gray><code>";

    private final LPC plugin;
    private volatile boolean enabled;
    private volatile boolean requirePermission;
    private volatile String hoverText = "";
    private volatile Map<String, String> replacements = Map.of();

    public EmojiReplacer(LPC plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        this.enabled = plugin.getConfig().getBoolean("emoji.enabled", true);
        this.requirePermission = plugin.getConfig().getBoolean("emoji.require-permission", false);
        // Defaults to on: a config written before this option existed has no such key, and the
        // hover is the whole point of the feature. Set it to "" to turn it off.
        this.hoverText = plugin.getConfig().getString("emoji.hover-text", DEFAULT_HOVER_TEXT);
        Map<String, String> map = new LinkedHashMap<>();
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("emoji.replacements");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                String value = section.getString(key);
                if (value != null && !key.isEmpty()) {
                    map.put(key, value);
                }
            }
        }
        this.replacements = Map.copyOf(map);
    }

    /** Applies emoji replacement to the message if enabled and permitted. */
    public Component apply(Player source, Component message) {
        if (!enabled || replacements.isEmpty()) {
            return message;
        }
        if (requirePermission && !source.hasPermission("lpc.emoji")) {
            return message;
        }
        return replace(message, replacements, hoverText);
    }

    /** Pure transform: replaces each shortcut literally within the component tree. */
    public static Component replace(Component message, Map<String, String> replacements) {
        return replace(message, replacements, "");
    }

    /**
     * Pure transform, optionally giving each glyph a hover naming the shortcut it replaced.
     *
     * @param hoverText MiniMessage template whose {@code <code>} tag becomes the shortcut, or empty
     *                  for no hover at all
     */
    public static Component replace(Component message, Map<String, String> replacements, String hoverText) {
        boolean withHover = hoverText != null && !hoverText.isEmpty();
        Component out = message;
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            Component glyph = Component.text(value);
            if (withHover) {
                glyph = glyph.hoverEvent(HoverEvent.showText(MiniMessage.miniMessage()
                        .deserialize(hoverText, Placeholder.unparsed("code", key))));
            }
            Component replacement = glyph;
            out = out.replaceText(builder -> builder.matchLiteral(key).replacement(replacement));
        }
        return out;
    }
}
