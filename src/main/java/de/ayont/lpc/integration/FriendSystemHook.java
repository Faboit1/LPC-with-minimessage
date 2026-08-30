package de.ayont.lpc.integration;

import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Optional bridge to FriendSystem's developer API, which answers the {@code friends} option of
 * {@code /showchatfrom} and {@code /allowmentions}.
 *
 * <p>FriendSystem is not published to a Maven repository, so it is reached through reflection
 * rather than a compile-time dependency: LPC still builds and runs on servers without it. Only the
 * documented public API ({@code com.faboit.friendsystem.api.FriendSystemAPI}) is touched, and only
 * its query half — FriendSystem states that queries read from memory and are safe to call from any
 * thread, which is what lets the async chat listeners use this.</p>
 *
 * <p>The API instance is looked up per call rather than cached, as FriendSystem's own documentation
 * recommends, so a reload that swaps the plugin out cannot leave LPC holding a dead object. Only
 * the reflected {@link Method} handles are kept.</p>
 */
public final class FriendSystemHook {

    /** The plugin name as it appears in FriendSystem's {@code plugin.yml}. */
    public static final String PLUGIN_NAME = "FriendSystem";

    private static final String API_CLASS = "com.faboit.friendsystem.api.FriendSystemAPI";

    private final Plugin plugin;

    private volatile Class<?> apiType;
    private volatile Method areFriendsMethod;
    /** Set once something goes wrong, so a broken hook cannot spam the console. */
    private volatile boolean broken;

    public FriendSystemHook(Plugin plugin) {
        this.plugin = plugin;
    }

    /** Whether FriendSystem is installed, enabled and answering. */
    public boolean isAvailable() {
        return api() != null;
    }

    /**
     * Whether the two players are friends, or {@code null} when FriendSystem cannot say.
     *
     * <p>Answering "unknown" separately from "no" in one call matters: this runs once per viewer per
     * chat message, and asking {@link #isAvailable()} first would double the work for each of
     * them.</p>
     */
    public Boolean friendship(UUID a, UUID b) {
        Object api = api();
        if (api == null) {
            return null;
        }
        try {
            return Boolean.TRUE.equals(areFriendsMethod.invoke(api, a, b));
        } catch (ReflectiveOperationException | RuntimeException error) {
            giveUp("FriendSystem rejected an areFriends(...) call", error);
            return null;
        }
    }

    /** The running API instance, or {@code null} when FriendSystem is absent or unusable. */
    private Object api() {
        if (broken || !plugin.getServer().getPluginManager().isPluginEnabled(PLUGIN_NAME)) {
            return null;
        }
        try {
            if (apiType == null) {
                Class<?> type = Class.forName(API_CLASS, true, getClass().getClassLoader());
                this.areFriendsMethod = type.getMethod("areFriends", UUID.class, UUID.class);
                this.apiType = type;
            }
            return plugin.getServer().getServicesManager().load(apiType);
        } catch (ClassNotFoundException | NoSuchMethodException | LinkageError error) {
            giveUp("FriendSystem is enabled but its API could not be reached", error);
            return null;
        }
    }

    private void giveUp(String what, Throwable error) {
        this.broken = true;
        plugin.getLogger().log(Level.WARNING, what
                + " - the 'friends' option will behave like the open option until the server restarts.", error);
    }
}
