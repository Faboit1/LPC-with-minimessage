package de.ayont.lpc.commands;

import de.ayont.lpc.LPC;
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
 * {@code /hideranks <on|off>} — hides everyone else's rank tags from the sender's own view.
 *
 * <p>This is a viewer-side setting: it changes what <em>you</em> see, never how you appear to
 * anybody else. With no argument it toggles.</p>
 */
public class HideRanksCommand implements CommandExecutor, TabCompleter {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final List<String> OPTIONS = List.of("on", "off");

    private final LPC plugin;

    public HideRanksCommand(LPC plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
                             @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.send(sender, MM.deserialize("<red>Only players have a chat view to change."));
            return true;
        }

        boolean current = plugin.getChatPreferences().hideRanks(player.getUniqueId());
        boolean hide;
        if (args.length == 0) {
            hide = !current;
        } else {
            String choice = args[0].toLowerCase(Locale.ROOT);
            switch (choice) {
                case "on", "true", "yes", "hide" -> hide = true;
                case "off", "false", "no", "show" -> hide = false;
                default -> {
                    plugin.send(player, MM.deserialize(
                            "<red>Unknown option '<white><given></white>'. Use <white>on</white> or "
                                    + "<white>off</white>.",
                            Placeholder.unparsed("given", args[0])));
                    return true;
                }
            }
        }

        plugin.getChatPreferences().hideRanks(player.getUniqueId(), hide);
        plugin.send(player, hide
                ? MM.deserialize("<green>Ranks are now <white>hidden</white> from your chat and tab list.")
                : MM.deserialize("<green>Ranks are now <white>visible</white> in your chat and tab list."));
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            String input = args[0].toLowerCase(Locale.ROOT);
            for (String option : OPTIONS) {
                if (option.startsWith(input)) {
                    completions.add(option);
                }
            }
        }
        return completions;
    }
}
