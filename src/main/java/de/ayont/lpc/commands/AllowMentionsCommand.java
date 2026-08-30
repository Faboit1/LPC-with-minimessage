package de.ayont.lpc.commands;

import de.ayont.lpc.LPC;
import de.ayont.lpc.chat.MentionPolicy;
import de.ayont.lpc.integration.FriendSystemHook;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /allowmentions <friends|all|nobody>} — picks who may ping the sender by name.
 *
 * <p>{@code friends} is answered by FriendSystem; without it installed the choice is still stored,
 * but the player is told it will not take effect yet.</p>
 */
public class AllowMentionsCommand implements CommandExecutor, TabCompleter {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final LPC plugin;

    public AllowMentionsCommand(LPC plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
                             @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.send(sender, MM.deserialize("<red>Only players can choose who may mention them."));
            return true;
        }

        if (args.length == 0) {
            MentionPolicy current = plugin.getChatPreferences().mentions(player.getUniqueId());
            plugin.send(player, MM.deserialize(
                    "<gray>Mentions from <white><mode></white> are allowed.",
                    Placeholder.unparsed("mode", current.key())));
            plugin.send(player, MM.deserialize(
                    "<gray>Change it with <white>/<label> <friends|all|nobody></white>.",
                    Placeholder.unparsed("label", label)));
            return true;
        }

        MentionPolicy policy = MentionPolicy.fromKey(args[0]);
        if (policy == null) {
            plugin.send(player, MM.deserialize(
                    "<red>Unknown option '<white><given></white>'. Use <white>friends</white>, "
                            + "<white>all</white> or <white>nobody</white>.",
                    Placeholder.unparsed("given", args[0])));
            return true;
        }

        plugin.getChatPreferences().mentions(player.getUniqueId(), policy);
        switch (policy) {
            case ALL -> plugin.send(player,
                    MM.deserialize("<green><white>Anyone</white> can now mention you."));
            case FRIENDS -> {
                plugin.send(player,
                        MM.deserialize("<green>Only <white>your friends</white> can now mention you."));
                warnIfNoFriendSystem(player);
            }
            case NOBODY -> plugin.send(player,
                    MM.deserialize("<green><white>Nobody</white> can mention you any more."));
        }
        return true;
    }

    private void warnIfNoFriendSystem(Player player) {
        if (plugin.getFriendSystemHook().isAvailable()) {
            return;
        }
        plugin.send(player, MM.deserialize(
                "<yellow><plugin> isn't running, so anyone can still mention you until it is installed.",
                Placeholder.unparsed("plugin", FriendSystemHook.PLUGIN_NAME)));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            String input = args[0].toLowerCase(Locale.ROOT);
            for (MentionPolicy policy : MentionPolicy.values()) {
                if (policy.key().startsWith(input)) {
                    completions.add(policy.key());
                }
            }
        }
        return completions;
    }
}
