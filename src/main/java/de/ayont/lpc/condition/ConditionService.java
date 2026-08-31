package de.ayont.lpc.condition;

import de.ayont.lpc.LPC;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Substitutes {@code %condition:name%} tokens with the text the named {@link Condition} resolves to.
 *
 * <p>Conditions are declared under {@code conditions:} in config.yml and may be used anywhere a
 * format is: {@code chat-format}, the per-group and per-track formats, and the join/quit/death
 * templates. See {@code CONDITIONS.md}.</p>
 *
 * <p>Substitution happens <em>before</em> the PlaceholderAPI pass, so placeholders inside a
 * condition's {@code yes}/{@code no} text are still expanded afterwards. The output is
 * operator-authored config text and is therefore trusted exactly as much as the format it is
 * substituted into — a player's own message is never routed through here.</p>
 *
 * <p>Thread-safety: the parsed table is snapshotted into a {@code volatile} field by
 * {@link #reload()} on the main thread and only read from the async chat thread.</p>
 */
public final class ConditionService {

    /** Names are restricted so a token cannot smuggle in arbitrary text. */
    private static final Pattern TOKEN = Pattern.compile("%condition:([A-Za-z0-9_.-]{1,64})%");

    /** A condition's output may reference other conditions; this caps the nesting. */
    private static final int MAX_DEPTH = 10;

    private final LPC plugin;
    private volatile Map<String, Condition> conditions = Map.of();
    private volatile boolean hasPapi;

    public ConditionService(LPC plugin) {
        this.plugin = plugin;
        reload();
    }

    /** Re-reads the {@code conditions:} section. Call after {@code reloadConfig()}. */
    public void reload() {
        this.hasPapi = plugin.getServer().getPluginManager().getPlugin("PlaceholderAPI") != null;
        this.conditions = parse(plugin.getConfig().getConfigurationSection("conditions"), plugin);
    }

    /** How many conditions are loaded; used by {@code /lpc version} style diagnostics and tests. */
    public int size() {
        return conditions.size();
    }

    public boolean isEmpty() {
        return conditions.isEmpty();
    }

    /** Reads and parses a {@code conditions:} section, warning about entries it cannot use. */
    public static Map<String, Condition> parse(ConfigurationSection section, LPC plugin) {
        if (section == null) {
            return Map.of();
        }
        Map<String, Condition> parsed = new LinkedHashMap<>();
        for (String name : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(name);
            if (entry == null) {
                warn(plugin, "Condition '" + name + "' is not a section and was ignored.");
                continue;
            }
            List<String> lines = entry.getStringList("conditions");
            List<Condition.Sub> subs = Condition.parseSubs(lines);
            if (subs.size() != lines.size()) {
                warn(plugin, "Condition '" + name + "' has " + (lines.size() - subs.size())
                        + " line(s) with no recognised operator; those were ignored.");
            }
            if (subs.isEmpty()) {
                warn(plugin, "Condition '" + name + "' has no usable sub-conditions and will always "
                        + "use its 'no' value.");
            }
            parsed.put(name, new Condition(
                    Condition.parseType(entry.getString("type")),
                    subs,
                    entry.getString("yes", ""),
                    entry.getString("no", "")));
        }
        return Map.copyOf(parsed);
    }

    private static void warn(LPC plugin, String message) {
        if (plugin != null) {
            plugin.getLogger().warning(message);
        }
    }

    /**
     * Replaces every {@code %condition:name%} in {@code text} for the given player.
     *
     * <p>Returns {@code text} untouched when no conditions are configured, so servers that do not
     * use the feature pay nothing for it.</p>
     */
    public String apply(Player player, String text) {
        if (text == null || conditions.isEmpty() || text.indexOf('%') < 0) {
            return text;
        }
        boolean papi = hasPapi;
        UnaryOperator<String> resolve = value ->
                papi ? PlaceholderAPI.setPlaceholders(player, value) : value;
        return apply(text, conditions, resolve, player::hasPermission);
    }

    /**
     * Pure substitution used by {@link #apply(Player, String)} and by unit tests.
     *
     * <p>An unknown condition name is left in place rather than blanked, so a typo shows up in chat
     * instead of silently producing nothing.</p>
     */
    public static String apply(String text, Map<String, Condition> conditions,
                               UnaryOperator<String> resolve, Predicate<String> hasPermission) {
        return apply(text, conditions, resolve, hasPermission, MAX_DEPTH);
    }

    private static String apply(String text, Map<String, Condition> conditions,
                                UnaryOperator<String> resolve, Predicate<String> hasPermission,
                                int depthLeft) {
        if (text == null || depthLeft <= 0 || conditions.isEmpty()) {
            return text;
        }
        Matcher matcher = TOKEN.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        boolean found = false;
        while (matcher.find()) {
            found = true;
            Condition condition = conditions.get(matcher.group(1));
            String replacement = condition == null
                    ? matcher.group()
                    : condition.evaluate(resolve, hasPermission);
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        if (!found) {
            return text;
        }
        matcher.appendTail(out);
        // A condition's output may itself name a condition; resolve those too, within the cap.
        return apply(out.toString(), conditions, resolve, hasPermission, depthLeft - 1);
    }

    /** An immutable view of the loaded conditions, for tests and diagnostics. */
    public Map<String, Condition> conditions() {
        return new HashMap<>(conditions);
    }
}
