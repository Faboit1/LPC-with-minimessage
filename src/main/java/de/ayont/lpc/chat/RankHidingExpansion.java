package de.ayont.lpc.chat;

import de.ayont.lpc.LPC;
import me.clip.placeholderapi.PlaceholderAPI;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import me.clip.placeholderapi.expansion.Relational;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * Exposes the {@code /hideranks} preference to other plugins.
 *
 * <p>The interesting one is relational. TAB builds a player's {@code tabprefix} once per viewer, so
 * a <em>relational</em> placeholder is the only way to give two people different views of the same
 * name plate: {@code %rel_lpc_rank_prefix%} resolves to the target's rank prefix normally, and to
 * nothing at all for a viewer who turned ranks off.</p>
 *
 * <p>Which placeholder counts as "the rank prefix" is a server decision, so it is read from
 * {@code rank-hiding.prefix-placeholder} in config.yml rather than hardcoded.</p>
 */
public final class RankHidingExpansion extends PlaceholderExpansion implements Relational {

    private final LPC plugin;

    public RankHidingExpansion(LPC plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "lpc";
    }

    @Override
    public @NotNull String getAuthor() {
        return "LPC";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    /** Non-relational: asks about one player's own setting. */
    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null) {
            return "";
        }
        return switch (params.toLowerCase(Locale.ROOT)) {
            case "hide_ranks", "hideranks" ->
                    Boolean.toString(plugin.getChatPreferences().hideRanks(player.getUniqueId()));
            case "show_ranks", "showranks" ->
                    Boolean.toString(!plugin.getChatPreferences().hideRanks(player.getUniqueId()));
            default -> null;
        };
    }

    /**
     * Relational: {@code one} is the viewer, {@code two} is the player being looked at.
     *
     * @return the target's rank prefix, or an empty string when the viewer hides ranks
     */
    @Override
    public String onPlaceholderRequest(Player one, Player two, @NotNull String params) {
        if (one == null) {
            return "";
        }
        boolean hidden = plugin.getChatPreferences().hideRanks(one.getUniqueId());
        return switch (params.toLowerCase(Locale.ROOT)) {
            case "rank_prefix", "rankprefix" -> {
                if (hidden || two == null) {
                    yield "";
                }
                String source = plugin.getConfig().getString("rank-hiding.prefix-placeholder", "%rank_prefix%");
                // Resolved against the target, so the viewer only decides whether it appears at all.
                yield source == null || source.isEmpty() ? "" : PlaceholderAPI.setPlaceholders(two, source);
            }
            case "hide_ranks", "hideranks" -> Boolean.toString(hidden);
            default -> null;
        };
    }
}
