package com.darkmortol.modopurga.command;

import com.darkmortol.modopurga.config.ConfigManager;
import com.darkmortol.modopurga.game.GameManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** Comando /purga: salir (leave), status, start, stop, reload. */
public class PurgaCommand implements CommandExecutor, TabCompleter {

    private final ConfigManager cfg;
    private final GameManager game;

    public PurgaCommand(ConfigManager cfg, GameManager game) {
        this.cfg = cfg;
        this.game = game;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase();
        switch (sub) {
            case "salir", "leave" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage(cfg.msg("only-players"));
                    return true;
                }
                if (!game.isParticipant(p.getUniqueId())) {
                    p.sendMessage(cfg.msg("not-in-game"));
                    return true;
                }
                game.leave(p, true);
            }
            case "status" -> sender.sendMessage(cfg.msg("status",
                    "state", game.getState().name(),
                    "players", String.valueOf(game.participantCount()),
                    "time", game.timeLeft()));
            case "start" -> {
                if (!isAdmin(sender)) return true;
                String error = game.start();
                sender.sendMessage(cfg.msg(error == null ? "start-ok" : error));
            }
            case "stop" -> {
                if (!isAdmin(sender)) return true;
                game.forceStop();
                sender.sendMessage(cfg.msg("stopped"));
            }
            case "reload" -> {
                if (!isAdmin(sender)) return true;
                cfg.reload();
                sender.sendMessage(cfg.msg("reloaded"));
            }
            default -> sender.sendMessage(cfg.msg("usage"));
        }
        return true;
    }

    private boolean isAdmin(CommandSender sender) {
        if (sender.hasPermission("modopurga.admin")) return true;
        sender.sendMessage(cfg.msg("no-permission"));
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length != 1) return out;
        List<String> options = new ArrayList<>(List.of("salir", "leave", "status"));
        if (sender.hasPermission("modopurga.admin")) options.addAll(List.of("start", "stop", "reload"));
        for (String o : options) {
            if (o.startsWith(args[0].toLowerCase())) out.add(o);
        }
        return out;
    }
}
