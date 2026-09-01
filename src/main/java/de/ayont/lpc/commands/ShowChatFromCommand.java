package de.ayont.lpc.commands;

import de.ayont.lpc.LPC;
import de.ayont.lpc.chat.ChatVisibility;
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
 * {@code /showchatfrom <everyone|friends|none>} — picks whose public chat the sender sees.
 *
 * <p>{@code friends} is answered by FriendSystem; without it installed the choice is still stored,
 * but the player is told it will not take effect yet.</p>
 */
public class ShowChatFromCommand implements CommandExecutor, TabCompleter {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final LPC plugin;

    public ShowChatFromCommand(LPC plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
                             @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.send(sender, MM.deserialize("<red>Only players can choose whose chat they see."));
            return true;
        }

        if (args.length == 0) {
            ChatVisibility current = plugin.getChatPreferences().visibility(player.getUniqueId());
            plugin.send(player, MM.deserialize(
                    "<gray>You are showing chat from <white><mode></white>.",
                    Placeholder.unparsed("mode", current.key())));
            plugin.send(player, MM.deserialize(
                    "<gray>Change it with <white>/<label> <everyone|friends|none></white>.",
                    Placeholder.unparsed("label", label)));
            return true;
        }

        ChatVisibility mode = ChatVisibility.fromKey(args[0]);
        if (mode == null) {
            plugin.send(player, MM.deserialize(
                    "<red>Unknown option '<white><given></white>'. Use <white>everyone</white>, "
                            + "<white>friends</white> or <white>none</white>.",
                    Placeholder.unparsed("given", args[0])));
            return true;
        }

        plugin.getChatPreferences().visibility(player.getUniqueId(), mode);
        switch (mode) {
            case EVERYONE -> plugin.send(player,
                    MM.deserialize("<green>You now see public chat from <white>everyone</white>."));
            case FRIENDS -> {
                plugin.send(player,
                        MM.deserialize("<green>You now see public chat from <white>your friends</white> only."));
                warnIfNoFriendSystem(player);
            }
            case NONE -> plugin.send(player,
                    MM.deserialize("<green>You no longer see public chat from <white>anyone</white>."));
        }
        return true;
    }

    private void warnIfNoFriendSystem(Player player) {
        if (plugin.getFriendSystemHook().isAvailable()) {
            return;
        }
        plugin.send(player, MM.deserialize(
                "<yellow><plugin> isn't running, so all chat stays visible until it is installed.",
                Placeholder.unparsed("plugin", FriendSystemHook.PLUGIN_NAME)));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            String input = args[0].toLowerCase(Locale.ROOT);
            for (ChatVisibility mode : ChatVisibility.values()) {
                if (mode.key().startsWith(input)) {
                    completions.add(mode.key());
                }
            }
        }
        return completions;
    }
}
