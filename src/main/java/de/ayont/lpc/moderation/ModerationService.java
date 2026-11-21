package de.ayont.lpc.moderation;

import de.ayont.lpc.LPC;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Gates chat messages on the async thread before rendering. Bundles mute, anti-spam cooldown,
 * repeat blocking, character whitelisting, caps, profanity and anti-advert filters behind one
 * {@link #process} call that returns {@link ModResult} (ALLOW / BLOCK / TRANSFORM).
 *
 * <p>Operates only on pre-parse raw plaintext via {@link TextChecks}; it never builds a component
 * from player text or invokes the trusted MiniMessage parser / PlaceholderAPI on player content.
 */
public final class ModerationService {

    private record Recent(String normalized, long time) {
    }

    private final LPC plugin;
    private final MuteService muteService;
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, Recent> lastMessages = new ConcurrentHashMap<>();

    private volatile boolean enabled;
    private volatile boolean spamEnabled;
    private volatile long cooldownMillis;
    private volatile String spamMessage;
    private volatile boolean repeatEnabled;
    private volatile long repeatExpiryMillis;
    private volatile String repeatMessage;
    private volatile boolean charactersEnabled;
    private volatile Pattern allowedCharacters;
    private volatile boolean charactersBlock;
    private volatile String charactersMessage;
    private volatile boolean capsEnabled;
    private volatile int capsMinLength;
    private volatile double capsMaxRatio;
    private volatile boolean capsBlock;
    private volatile String capsMessage;
    private volatile boolean profanityEnabled;
    private volatile boolean profanityBlock;
    private volatile List<String> profanityWords;
    private volatile List<String> profanityNormalized;
    private volatile List<Pattern> profanityRegexes = List.of();
    private volatile char profanityMaskChar;
    private volatile String profanityMessage;
    private volatile boolean advertEnabled;
    private volatile boolean advertBlock;
    private volatile boolean advertDetectIp;
    private volatile List<String> advertAllowlist;
    private volatile String advertMask;
    private volatile String advertMessage;

    public ModerationService(LPC plugin, MuteService muteService) {
        this.plugin = plugin;
        this.muteService = muteService;
        reload();
    }

    public void reload() {
        var config = plugin.getConfig();
        this.enabled = config.getBoolean("moderation.enabled", false);

        this.spamEnabled = config.getBoolean("anti-spam.enabled", true);
        this.cooldownMillis = config.getLong("anti-spam.cooldown-millis", 1500);
        this.spamMessage = config.getString("anti-spam.message", "<red>Please wait <yellow><seconds>s</yellow> before chatting again.");

        this.repeatEnabled = config.getBoolean("repeat-filter.enabled", true);
        this.repeatExpiryMillis = config.getLong("repeat-filter.expiry-millis", 30_000);
        this.repeatMessage = config.getString("repeat-filter.message", "<red>Please don't repeat the same message.");

        this.charactersEnabled = config.getBoolean("character-filter.enabled", false);
        this.allowedCharacters = compileAllowed(config.getString("character-filter.allowed-characters", DEFAULT_ALLOWED));
        this.charactersBlock = !"STRIP".equalsIgnoreCase(config.getString("character-filter.action", "BLOCK"));
        this.charactersMessage = config.getString("character-filter.message",
                "<red>That character isn't allowed in chat: <yellow><character></yellow>");

        this.capsEnabled = config.getBoolean("caps-filter.enabled", true);
        this.capsMinLength = config.getInt("caps-filter.min-length", 8);
        this.capsMaxRatio = config.getDouble("caps-filter.max-uppercase-ratio", 0.7);
        this.capsBlock = "BLOCK".equalsIgnoreCase(config.getString("caps-filter.action", "LOWERCASE"));
        this.capsMessage = config.getString("caps-filter.message", "<red>Please don't shout.");

        this.profanityEnabled = config.getBoolean("profanity-filter.enabled", false);
        this.profanityBlock = "BLOCK".equalsIgnoreCase(config.getString("profanity-filter.action", "MASK"));
        this.profanityWords = List.copyOf(config.getStringList("profanity-filter.words"));
        this.profanityNormalized = this.profanityWords.stream().map(TextChecks::normalize).toList();
        this.profanityRegexes = loadProfanityRegexes(config.getStringList("profanity-filter.regex"),
                config.getStringList("profanity-filter.regex-files"));
        String maskChar = config.getString("profanity-filter.mask-char", "*");
        this.profanityMaskChar = maskChar.isEmpty() ? '*' : maskChar.charAt(0);
        this.profanityMessage = config.getString("profanity-filter.message", "<red>Watch your language.");

        this.advertEnabled = config.getBoolean("anti-advert.enabled", false);
        this.advertBlock = "BLOCK".equalsIgnoreCase(config.getString("anti-advert.action", "REDACT"));
        this.advertDetectIp = config.getBoolean("anti-advert.detect-ip", true);
        this.advertAllowlist = List.copyOf(config.getStringList("anti-advert.allowlist"));
        this.advertMask = config.getString("anti-advert.mask", "[link removed]");
        this.advertMessage = config.getString("anti-advert.message", "<red>Advertising is not allowed.");
    }

    /**
     * Evaluates all filters against the raw message.
     *
     * @return BLOCK (cancel + notice), TRANSFORM (cleaned text) or ALLOW.
     */
    public ModResult process(Player player, String raw) {
        long now = System.currentTimeMillis();

        if (muteService.isMuted(player, now)) {
            return ModResult.block(muteService.notice());
        }

        if (!enabled) {
            return ModResult.allow();
        }

        UUID id = player.getUniqueId();

        if (spamEnabled && !player.hasPermission("lpc.bypass.spam")) {
            Long last = cooldowns.get(id);
            if (last != null && now - last < cooldownMillis) {
                long secondsLeft = Math.max(1, (cooldownMillis - (now - last) + 999) / 1000);
                return ModResult.block(notice(spamMessage, "seconds", Long.toString(secondsLeft)));
            }
            cooldowns.put(id, now);
        }

        String normalized = TextChecks.normalize(raw);
        if (repeatEnabled && !player.hasPermission("lpc.bypass.repeat")) {
            Recent recent = lastMessages.get(id);
            if (recent != null && now - recent.time() < repeatExpiryMillis && recent.normalized().equals(normalized)) {
                return ModResult.block(notice(repeatMessage));
            }
            lastMessages.put(id, new Recent(normalized, now));
        }

        String text = raw;

        // Runs before the other text filters so they only ever see characters the server permits.
        if (charactersEnabled && !player.hasPermission("lpc.bypass.characters")) {
            int refused = TextChecks.firstDisallowed(text, allowedCharacters);
            if (refused >= 0) {
                if (charactersBlock) {
                    return ModResult.block(notice(charactersMessage, "character",
                            new String(Character.toChars(refused))));
                }
                text = TextChecks.stripDisallowed(text, allowedCharacters);
                if (text.isBlank()) {
                    return ModResult.block(notice(charactersMessage, "character",
                            new String(Character.toChars(refused))));
                }
            }
        }

        if (capsEnabled && !player.hasPermission("lpc.bypass.caps")
                && TextChecks.isShout(text, capsMinLength, capsMaxRatio)) {
            if (capsBlock) {
                return ModResult.block(notice(capsMessage));
            }
            text = text.toLowerCase(Locale.ROOT);
        }

        if (profanityEnabled && !player.hasPermission("lpc.bypass.profanity")) {
            String cleaned = TextChecks.cleanForRegex(text);
            boolean regexHit = TextChecks.matchesAnyRegex(cleaned, profanityRegexes);
            boolean wordHit = TextChecks.containsAny(TextChecks.normalize(text), profanityNormalized);
            if (regexHit || wordHit) {
                if (profanityBlock) {
                    return ModResult.block(notice(profanityMessage));
                }
                String masked = TextChecks.maskRegexMatches(text, profanityRegexes, profanityMaskChar);
                if (regexHit && TextChecks.matchesAnyRegex(TextChecks.cleanForRegex(masked), profanityRegexes)) {
                    // The regex only recognises the word after cleaning (hidden colour codes, invisible or
                    // fullwidth characters), so masking the raw text cannot remove it: refuse instead.
                    return ModResult.block(notice(profanityMessage));
                }
                text = TextChecks.maskWords(masked, profanityWords, profanityMaskChar);
            }
        }

        if (advertEnabled && !player.hasPermission("lpc.bypass.advert")
                && TextChecks.containsAdvert(text, advertDetectIp, advertAllowlist)) {
            if (advertBlock) {
                return ModResult.block(notice(advertMessage));
            }
            text = TextChecks.maskAdvert(text, advertDetectIp, advertAllowlist, advertMask);
        }

        return text.equals(raw) ? ModResult.allow() : ModResult.transform(text);
    }

    /**
     * Describes what the profanity filter makes of {@code message}, ignoring bypass permissions and the
     * per-filter switches. Backs {@code /lpc filtertest} so admins can try a regex from the console.
     */
    public String describeProfanity(String message) {
        String cleaned = TextChecks.cleanForRegex(message);
        String regex = TextChecks.firstRegexMatch(cleaned, profanityRegexes);
        boolean word = TextChecks.containsAny(TextChecks.normalize(message), profanityNormalized);
        if (regex == null && !word) {
            return "ALLOWED - no regex (" + profanityRegexes.size() + " loaded) or word matched";
        }
        String hit = regex != null ? "regex matched \"" + regex + "\"" : "a blocked word matched";
        if (profanityBlock) {
            return "BLOCKED - " + hit;
        }
        String masked = TextChecks.maskRegexMatches(message, profanityRegexes, profanityMaskChar);
        if (regex != null && TextChecks.matchesAnyRegex(TextChecks.cleanForRegex(masked), profanityRegexes)) {
            return "BLOCKED - " + hit + " (cannot be masked in place)";
        }
        return "MASKED - " + hit + " -> " + TextChecks.maskWords(masked, profanityWords, profanityMaskChar);
    }

    /** @return how many regular expressions the profanity filter currently has loaded. */
    public int profanityRegexCount() {
        return profanityRegexes.size();
    }

    /**
     * Gathers regexes from the inline {@code profanity-filter.regex} list and from each file named in
     * {@code profanity-filter.regex-files} (inside the plugin folder; one expression per line, blank lines
     * and lines starting with {@code #} ignored). A bad entry or missing file only logs a warning.
     */
    private List<Pattern> loadProfanityRegexes(List<String> inline, List<String> files) {
        List<String> sources = new ArrayList<>(inline);
        Path dataFolder = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        int fileCount = 0;
        for (String name : files) {
            if (name == null || name.isBlank()) {
                continue;
            }
            Path file = dataFolder.resolve(name.trim()).normalize();
            if (!file.startsWith(dataFolder)) {
                plugin.getLogger().warning("profanity-filter.regex-files: '" + name + "' is outside the plugin folder - skipped.");
                continue;
            }
            if (!Files.isRegularFile(file)) {
                plugin.getLogger().warning("profanity-filter.regex-files: '" + name + "' was not found in " + dataFolder + " - skipped.");
                continue;
            }
            try {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String trimmed = line.strip();
                    if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                        sources.add(trimmed);
                    }
                }
                fileCount++;
            } catch (IOException unreadable) {
                plugin.getLogger().warning("profanity-filter.regex-files: could not read '" + name + "': " + unreadable.getMessage());
            }
        }
        List<Pattern> compiled = TextChecks.compileRegexes(sources, (source, reason) ->
                plugin.getLogger().warning("profanity-filter: ignoring an invalid regex (" + reason + "): "
                        + (source.length() > 60 ? source.substring(0, 60) + "..." : source)));
        if (!compiled.isEmpty()) {
            plugin.getLogger().info("Profanity filter: loaded " + compiled.size() + " regex pattern(s) ("
                    + inline.size() + " inline, " + fileCount + " file(s)).");
        }
        return compiled;
    }

    /** Printable ASCII: the range the default Minecraft font is guaranteed to render. */
    private static final String DEFAULT_ALLOWED = "[\\x20-\\x7E]";

    /** Falls back to printable ASCII when an admin's regex does not compile, rather than failing. */
    private Pattern compileAllowed(String expression) {
        try {
            return Pattern.compile(expression == null || expression.isEmpty() ? DEFAULT_ALLOWED : expression);
        } catch (PatternSyntaxException invalid) {
            plugin.getLogger().warning("character-filter.allowed-characters is not a valid regular expression ("
                    + invalid.getDescription() + ") - falling back to printable ASCII.");
            return Pattern.compile(DEFAULT_ALLOWED);
        }
    }

    private static Component notice(String raw) {
        return MiniMessage.miniMessage().deserialize(raw);
    }

    private static Component notice(String raw, String key, String value) {
        return MiniMessage.miniMessage().deserialize(raw, Placeholder.unparsed(key, value));
    }
}
