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
    static final Pattern TOKEN = Pattern.compile("%condition:([A-Za-z0-9_.-]{1,64})%");

    /**
     * Same grammar as {@link #TOKEN}, but resolved against the player <em>reading</em> the line
     * rather than the one who wrote it. Chat is rendered once per viewer, so a format may mix both:
     * {@code %vcondition:x%} asks about the reader, {@code %condition:x%} about the speaker.
     */
    static final Pattern VIEWER_TOKEN = Pattern.compile("%vcondition:([A-Za-z0-9_.-]{1,64})%");

    /** A condition's output may reference other conditions; this caps the nesting. */
    static final int MAX_DEPTH = 10;

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
                    branch(entry, "yes", "true"),
                    branch(entry, "no", "false")));
        }
        return Map.copyOf(parsed);
    }

    /**
     * Reads a condition branch.
     *
     * <p>YAML 1.1 — which SnakeYAML implements — treats bare {@code yes} and {@code no} as booleans,
     * so an unquoted {@code yes:} key arrives here as {@code "true"}. Both spellings are accepted so
     * the documented syntax works whether or not the operator quoted the key.</p>
     */
    private static String branch(ConfigurationSection entry, String key, String coerced) {
        String value = entry.getString(key);
        return value != null ? value : entry.getString(coerced, "");
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
        return apply(player, text, TOKEN);
    }

    /**
     * Replaces every {@code %vcondition:name%} in {@code text}, resolved against {@code viewer} —
     * the player who will read the line, not the one who wrote it.
     *
     * <p>Run this <em>before</em> the PlaceholderAPI pass, exactly like {@link #apply(Player,
     * String)}. The reader decides which branch is taken, but any placeholder inside that branch is
     * still expanded against the speaker afterwards, so
     * {@code no: "%rank_prefix% "} shows the <em>speaker's</em> rank to readers who left it on.</p>
     */
    public String applyViewer(Player viewer, String text) {
        return apply(viewer, text, VIEWER_TOKEN);
    }

    private String apply(Player player, String text, Pattern token) {
        if (player == null || text == null || conditions.isEmpty() || text.indexOf('%') < 0) {
            return text;
        }
        boolean papi = hasPapi;
        UnaryOperator<String> resolve = value ->
                papi ? PlaceholderAPI.setPlaceholders(player, value) : value;
        return apply(text, conditions, resolve, player::hasPermission, MAX_DEPTH, token);
    }

    /**
     * Pure substitution used by {@link #apply(Player, String)} and by unit tests.
     *
     * <p>An unknown condition name is left in place rather than blanked, so a typo shows up in chat
     * instead of silently producing nothing.</p>
     */
    public static String apply(String text, Map<String, Condition> conditions,
                               UnaryOperator<String> resolve, Predicate<String> hasPermission) {
        return apply(text, conditions, resolve, hasPermission, MAX_DEPTH, TOKEN);
    }

    /** Package-private so tests can drive either namespace directly. */
    static String apply(String text, Map<String, Condition> conditions,
                        UnaryOperator<String> resolve, Predicate<String> hasPermission,
                        int depthLeft, Pattern token) {
        if (text == null || depthLeft <= 0 || conditions.isEmpty()) {
            return text;
        }
        Matcher matcher = token.matcher(text);
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
        // Nesting stays in the same namespace: a viewer condition's branches may only name other
        // viewer conditions, so the "who is being asked about" never silently flips mid-resolution.
        return apply(out.toString(), conditions, resolve, hasPermission, depthLeft - 1, token);
    }

    /** An immutable view of the loaded conditions, for tests and diagnostics. */
    public Map<String, Condition> conditions() {
        return new HashMap<>(conditions);
    }
}
