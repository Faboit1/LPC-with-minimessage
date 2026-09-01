package de.ayont.lpc;

import de.ayont.lpc.chat.ChatFormatService;
import de.ayont.lpc.chat.ChatPreferences;
import de.ayont.lpc.chat.ChatVisibility;
import de.ayont.lpc.chat.EmojiReplacer;
import de.ayont.lpc.chat.MentionPolicy;
import de.ayont.lpc.chat.ItemPlaceholder;
import de.ayont.lpc.chat.MentionService;
import de.ayont.lpc.chat.RankHidingExpansion;
import de.ayont.lpc.chat.UrlLinkifier;
import de.ayont.lpc.commands.AllowMentionsCommand;
import de.ayont.lpc.commands.HideRanksCommand;
import de.ayont.lpc.commands.LPCCommand;
import de.ayont.lpc.commands.ShowChatFromCommand;
import de.ayont.lpc.condition.ConditionService;
import de.ayont.lpc.listener.AsyncChatListener;
import de.ayont.lpc.listener.ConnectionListener;
import de.ayont.lpc.listener.SpigotChatListener;
import de.ayont.lpc.integration.FriendSystemHook;
import de.ayont.lpc.moderation.ModerationService;
import de.ayont.lpc.moderation.MuteService;
import de.ayont.lpc.scheduler.Scheduler;
import de.ayont.lpc.scheduler.Schedulers;
import de.ayont.lpc.update.UpdateChecker;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

public final class LPC extends JavaPlugin {

    private static final LegacyComponentSerializer LEGACY_SERIALIZER = LegacyComponentSerializer.builder()
            .character('§')
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();

    private boolean paper;
    private boolean folia;
    private Scheduler scheduler;
    private ChatFormatService chatFormatService;
    private MuteService muteService;
    private ModerationService moderationService;
    private EmojiReplacer emojiReplacer;
    private UrlLinkifier urlLinkifier;
    private MentionService mentionService;
    private ConditionService conditionService;
    private ChatPreferences chatPreferences;
    private RankHidingExpansion rankHidingExpansion;
    private FriendSystemHook friendSystemHook;

    public static LegacyComponentSerializer getLegacySerializer() {
        return LEGACY_SERIALIZER;
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.paper = detectPaper();
        this.folia = detectFolia();
        this.scheduler = Schedulers.create(this);
        // Built before the format service, which calls into it on every rendered line.
        this.conditionService = new ConditionService(this);
        this.chatFormatService = new ChatFormatService(this);
        this.muteService = new MuteService(this);
        this.moderationService = new ModerationService(this, muteService);
        this.emojiReplacer = new EmojiReplacer(this);
        this.urlLinkifier = new UrlLinkifier(this);
        this.mentionService = new MentionService(this);
        this.chatPreferences = new ChatPreferences(this);
        this.friendSystemHook = new FriendSystemHook(this);
        logFriendSystemHook();
        if (!conditionService.isEmpty()) {
            getLogger().info("Loaded " + conditionService.size() + " condition(s) for %condition:name% tokens.");
        }
        registerCommand();
        registerListeners();
        registerRankHidingExpansion();
        startUpdateChecker();
        logRuntimePlatform();
    }

    /** Logs the detected server + Java version — the single universal jar runs on many, so make
     *  the actual runtime platform visible for support. */
    private void logRuntimePlatform() {
        getLogger().info("Running on " + getServer().getName() + " (API " + getServer().getBukkitVersion()
                + ") on Java " + System.getProperty("java.version")
                + (paper ? " [Paper/Adventure chat]" : " [Spigot/legacy chat]")
                + (folia ? " [Folia]" : ""));
    }

    /**
     * Tells a player why their {@code [item]} did not resolve, when {@code use-item-placeholder} is
     * enabled but they lack the {@code lpc.itemplaceholder} permission. Called once per chat message
     * (not per viewer), so it never spams.
     */
    public void maybeItemPlaceholderHint(Player player, String message) {
        if (!getConfig().getBoolean("use-item-placeholder", false)) {
            return;
        }
        if (player.hasPermission("lpc.itemplaceholder")) {
            return;
        }
        if (!ItemPlaceholder.containsToken(message)) {
            return;
        }
        send(player, MiniMessage.miniMessage().deserialize(
                "<dark_gray>[<gradient:#B754F4:#FC00FF>LPC</gradient>] <yellow>Ask an admin for the "
                        + "<white>lpc.itemplaceholder</white> permission to use <white>[item]</white> in chat."));
    }

    public boolean isPaper() {
        return paper;
    }

    public boolean isFolia() {
        return folia;
    }

    public Scheduler getScheduler() {
        return scheduler;
    }

    /**
     * Publishes {@code %lpc_hide_ranks%} and the relational {@code %rel_lpc_rank_prefix%} so TAB (or
     * anything else) can honour the viewer's {@code /hideranks} choice. Optional: without
     * PlaceholderAPI, chat still works via {@code %vcondition:...%}.
     */
    private void registerRankHidingExpansion() {
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") == null) {
            return;
        }
        try {
            this.rankHidingExpansion = new RankHidingExpansion(this);
            if (rankHidingExpansion.register()) {
                getLogger().info("Registered %lpc_hide_ranks% and relational %rel_lpc_rank_prefix%.");
            }
        } catch (Throwable failure) {
            getLogger().warning("Could not register the LPC placeholder expansion: " + failure.getMessage());
        }
    }

    @Override
    public void onDisable() {
        if (rankHidingExpansion != null) {
            try {
                rankHidingExpansion.unregister();
            } catch (Throwable ignored) {
                // PlaceholderAPI may already be gone during shutdown.
            }
        }
        if (chatPreferences != null) {
            // Written inline: the scheduler will not accept new work while disabling.
            chatPreferences.save();
        }
        if (scheduler != null) {
            scheduler.cancelAll();
        }
    }

    /** Makes the state of the optional FriendSystem hook visible at startup, not just in-game. */
    private void logFriendSystemHook() {
        if (friendSystemHook.isAvailable()) {
            getLogger().info("Hooked into " + FriendSystemHook.PLUGIN_NAME
                    + " - the 'friends' option of /showchatfrom and /allowmentions is active.");
        } else {
            getLogger().info(FriendSystemHook.PLUGIN_NAME + " not found - the 'friends' option of "
                    + "/showchatfrom and /allowmentions will keep showing all chat.");
        }
    }

    public ChatPreferences getChatPreferences() {
        return chatPreferences;
    }

    public FriendSystemHook getFriendSystemHook() {
        return friendSystemHook;
    }

    /**
     * Whether {@code viewer} wants to see {@code speaker}'s public chat, per their
     * {@code /showchatfrom} setting. A player always sees their own messages.
     */
    public boolean maySee(Player speaker, Player viewer) {
        java.util.UUID watching = viewer.getUniqueId();
        if (watching.equals(speaker.getUniqueId())) {
            return true;
        }
        return switch (chatPreferences.visibility(watching)) {
            case EVERYONE -> true;
            case NONE -> false;
            // Friends-only, but nothing can answer "are these two friends?" - show the message
            // rather than silently cutting the player off from chat entirely.
            case FRIENDS -> !Boolean.FALSE.equals(
                    friendSystemHook.friendship(watching, speaker.getUniqueId()));
        };
    }

    /** Whether {@code mentioned}'s {@code /allowmentions} setting lets {@code speaker} ping them. */
    public boolean mayMention(Player mentioned, Player speaker) {
        if (mentioned.getUniqueId().equals(speaker.getUniqueId())) {
            return false;
        }
        return switch (chatPreferences.mentions(mentioned.getUniqueId())) {
            case ALL -> true;
            case NOBODY -> false;
            // Same fallback as /showchatfrom: when nothing can answer, let the mention through.
            case FRIENDS -> !Boolean.FALSE.equals(
                    friendSystemHook.friendship(mentioned.getUniqueId(), speaker.getUniqueId()));
        };
    }

    public ChatFormatService getChatFormatService() {
        return chatFormatService;
    }

    public ConditionService getConditionService() {
        return conditionService;
    }

    public ModerationService getModerationService() {
        return moderationService;
    }

    public MuteService getMuteService() {
        return muteService;
    }

    public EmojiReplacer getEmojiReplacer() {
        return emojiReplacer;
    }

    public UrlLinkifier getUrlLinkifier() {
        return urlLinkifier;
    }

    public MentionService getMentionService() {
        return mentionService;
    }

    /** Re-reads config-derived state for every service. Call after {@code reloadConfig()}. */
    public void reloadServices() {
        conditionService.reload();
        chatFormatService.reload();
        muteService.reload();
        moderationService.reload();
        emojiReplacer.reload();
        urlLinkifier.reload();
        mentionService.reload();
    }

    /** @return whether chat formatting is disabled in the given world. */
    public boolean isDisabledWorld(String worldName) {
        for (String world : getConfig().getStringList("disabled-worlds")) {
            if (world.equalsIgnoreCase(worldName)) {
                return true;
            }
        }
        return false;
    }

    /** Resolves a player's display name as a component on either platform. */
    @SuppressWarnings("deprecation") // getDisplayName() is the Spigot fallback
    public Component displayNameOf(Player player) {
        return paper ? player.displayName() : LEGACY_SERIALIZER.deserialize(player.getDisplayName());
    }

    /** Sends a component to a sender, falling back to legacy text on Spigot. */
    public void send(CommandSender target, Component component) {
        if (paper) {
            target.sendMessage(component);
        } else {
            target.sendMessage(LEGACY_SERIALIZER.serialize(component));
        }
    }

    private void registerCommand() {
        PluginCommand command = getCommand("lpc");
        if (command == null) {
            getLogger().warning("Command 'lpc' is missing from plugin.yml; commands are unavailable.");
            return;
        }
        LPCCommand executor = new LPCCommand(this);
        command.setExecutor(executor);
        command.setTabCompleter(executor);

        bind("showchatfrom", new ShowChatFromCommand(this));
        bind("allowmentions", new AllowMentionsCommand(this));
        bind("hideranks", new HideRanksCommand(this));
    }

    /** Attaches one of the per-player chat setting commands, if plugin.yml declares it. */
    private <T extends org.bukkit.command.CommandExecutor & org.bukkit.command.TabCompleter> void bind(
            String name, T executor) {
        PluginCommand command = getCommand(name);
        if (command == null) {
            getLogger().warning("Command '" + name + "' is missing from plugin.yml; it is unavailable.");
            return;
        }
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    private void registerListeners() {
        PluginManager pluginManager = getServer().getPluginManager();
        if (paper) {
            pluginManager.registerEvents(new AsyncChatListener(this), this);
        } else {
            pluginManager.registerEvents(new SpigotChatListener(this), this);
        }
        pluginManager.registerEvents(new ConnectionListener(this), this);
    }

    private void startUpdateChecker() {
        if (!getConfig().getBoolean("update-checker", true)) {
            return;
        }
        UpdateChecker updateChecker = new UpdateChecker(this);
        getServer().getPluginManager().registerEvents(updateChecker, this);
        updateChecker.checkAsync();
    }

    private boolean detectPaper() {
        try {
            Class.forName("io.papermc.paper.event.player.AsyncChatEvent");
            getLogger().info("Paper API detected — using Adventure chat rendering.");
            return true;
        } catch (ClassNotFoundException notPaper) {
            getLogger().info("Spigot API detected — using legacy chat rendering.");
            return false;
        }
    }

    private boolean detectFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            getLogger().info("Folia detected — using regionized scheduling.");
            return true;
        } catch (ClassNotFoundException notFolia) {
            return false;
        }
    }
}
