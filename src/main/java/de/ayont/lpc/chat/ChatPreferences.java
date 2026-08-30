package de.ayont.lpc.chat;

import de.ayont.lpc.LPC;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * The per-player chat settings behind {@code /showchatfrom} and {@code /allowmentions}.
 *
 * <p>Held in memory so the chat listeners never touch the disk on the chat thread, and mirrored
 * into {@code chat-settings.yml} through {@link de.ayont.lpc.scheduler.Scheduler#runAsync} whenever
 * somebody changes theirs. Players who left both settings alone are not written out, so the file
 * only ever lists players who opted into something.</p>
 */
public final class ChatPreferences {

    private static final String FILE_NAME = "chat-settings.yml";
    private static final String SHOW_CHAT_FROM = "show-chat-from";
    private static final String ALLOW_MENTIONS = "allow-mentions";

    /** One player's settings. Immutable: an update replaces the whole record. */
    private record Prefs(ChatVisibility visibility, MentionPolicy mentions) {

        static final Prefs DEFAULTS = new Prefs(ChatVisibility.EVERYONE, MentionPolicy.ALL);

        boolean isDefault() {
            return equals(DEFAULTS);
        }
    }

    private final LPC plugin;
    private final File file;
    private final Map<UUID, Prefs> players = new ConcurrentHashMap<>();

    public ChatPreferences(LPC plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), FILE_NAME);
        load();
    }

    /** Reads the file into memory. Unreadable or unknown entries are skipped, never fatal. */
    public void load() {
        players.clear();
        if (!file.isFile()) {
            return;
        }
        FileConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (String key : yaml.getKeys(false)) {
            ConfigurationSection entry = yaml.getConfigurationSection(key);
            if (entry == null) {
                continue;
            }
            UUID uuid;
            try {
                uuid = UUID.fromString(key);
            } catch (IllegalArgumentException notAUuid) {
                plugin.getLogger().warning("Skipping malformed player id '" + key + "' in " + FILE_NAME + ".");
                continue;
            }
            ChatVisibility visibility = ChatVisibility.fromKey(entry.getString(SHOW_CHAT_FROM));
            MentionPolicy mentions = MentionPolicy.fromKey(entry.getString(ALLOW_MENTIONS));
            Prefs prefs = new Prefs(
                    visibility == null ? Prefs.DEFAULTS.visibility() : visibility,
                    mentions == null ? Prefs.DEFAULTS.mentions() : mentions);
            if (!prefs.isDefault()) {
                players.put(uuid, prefs);
            }
        }
    }

    public ChatVisibility visibility(UUID player) {
        return players.getOrDefault(player, Prefs.DEFAULTS).visibility();
    }

    public void visibility(UUID player, ChatVisibility mode) {
        update(player, prefs -> new Prefs(mode == null ? Prefs.DEFAULTS.visibility() : mode, prefs.mentions()));
    }

    public MentionPolicy mentions(UUID player) {
        return players.getOrDefault(player, Prefs.DEFAULTS).mentions();
    }

    public void mentions(UUID player, MentionPolicy policy) {
        update(player, prefs -> new Prefs(prefs.visibility(), policy == null ? Prefs.DEFAULTS.mentions() : policy));
    }

    private void update(UUID player, java.util.function.UnaryOperator<Prefs> change) {
        Prefs updated = change.apply(players.getOrDefault(player, Prefs.DEFAULTS));
        if (updated.isDefault()) {
            players.remove(player);
        } else {
            players.put(player, updated);
        }
        plugin.getScheduler().runAsync(this::save);
    }

    /** Rewrites the whole file; synchronized so two queued saves cannot interleave. */
    public synchronized void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, Prefs> entry : players.entrySet()) {
            Prefs prefs = entry.getValue();
            if (prefs.isDefault()) {
                continue;
            }
            String id = entry.getKey().toString();
            yaml.set(id + "." + SHOW_CHAT_FROM, prefs.visibility().key());
            yaml.set(id + "." + ALLOW_MENTIONS, prefs.mentions().key());
        }
        File folder = file.getParentFile();
        if (folder != null && !folder.isDirectory() && !folder.mkdirs()) {
            plugin.getLogger().warning("Could not create " + folder + " to save " + FILE_NAME + ".");
            return;
        }
        try {
            yaml.save(file);
        } catch (IOException failure) {
            plugin.getLogger().log(Level.WARNING, "Could not save " + FILE_NAME + ".", failure);
        }
    }
}
