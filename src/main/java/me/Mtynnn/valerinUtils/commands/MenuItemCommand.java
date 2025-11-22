package me.Mtynnn.valerinUtils.commands;

import me.Mtynnn.valerinUtils.ValerinUtils;
import me.Mtynnn.valerinUtils.modules.menuitem.MenuItemModule;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class MenuItemCommand implements CommandExecutor, TabCompleter {

    private final ValerinUtils plugin;
    private final MenuItemModule module;

    public MenuItemCommand(ValerinUtils plugin, MenuItemModule module) {
        this.plugin = plugin;
        this.module = module;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

        if (!(sender instanceof Player player)) {
            sender.sendMessage("Solo jugadores pueden usar este comando.");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage("§aUso: /" + label + " <on|off|toggle>");
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "on" -> {
                module.setDisabled(player, false);
                sender.sendMessage("§aItem de menú activado.");
            }
            case "off" -> {
                module.setDisabled(player, true);
                sender.sendMessage("§cItem de menú desactivado.");
            }
            case "toggle" -> {
                boolean disabled = module.isDisabled(player);
                module.setDisabled(player, !disabled);
                sender.sendMessage(!disabled
                        ? "§cItem de menú desactivado."
                        : "§aItem de menú activado.");
            }
            default -> sender.sendMessage("§cUso: /" + label + " <on|off|toggle>");
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> base = Arrays.asList("on", "off", "toggle");
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            for (String s : base) {
                if (s.startsWith(prefix)) out.add(s);
            }
            return out;
        }
        return List.of();
    }
}
