package com.darkmortol.modopurga.listener;

import com.darkmortol.modopurga.ModoPurgaPlugin;
import com.darkmortol.modopurga.game.GameManager;
import com.darkmortol.modopurga.player.PlayerManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/** Detecta entradas/salidas del mundo del evento, muertes, reconexiones y respawn. */
public class PlayerListener implements Listener {

    private final ModoPurgaPlugin plugin;
    private final GameManager game;
    private final PlayerManager players;

    public PlayerListener(ModoPurgaPlugin plugin, GameManager game, PlayerManager players) {
        this.plugin = plugin;
        this.game = game;
        this.players = players;
    }

    /** Cambio de mundo (por /mv tp o cualquier medio). */
    @EventHandler
    public void onChangedWorld(PlayerChangedWorldEvent e) {
        if (!game.isEnabled()) return;
        Player p = e.getPlayer();
        if (game.isEventWorld(p.getWorld())) {
            game.join(p);
        } else if (game.isEventWorld(e.getFrom()) && game.isParticipant(p.getUniqueId())) {
            game.leave(p, false); // ya se fue del mundo: solo restauramos
        }
    }

    /** Si alguien se desconecto en plena Purga, al volver recupera su Survival. */
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline() || game.isParticipant(p.getUniqueId())) return;
            if (players.hasSnapshot(p.getUniqueId())) {
                game.sendToFallback(p);
                players.restore(p);
            } else if (game.isEnabled() && game.isEventWorld(p.getWorld())) {
                game.join(p); // entro directo al mundo del evento
            }
        }, 5L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (game.isParticipant(e.getPlayer().getUniqueId())) game.disconnect(e.getPlayer());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getPlayer();
        if (!game.isParticipant(p.getUniqueId())) return;
        e.getDrops().removeIf(players::isTracker); // la brujula no se suelta
        game.eliminate(p);
    }

    /** Al reaparecer, el eliminado vuelve al Survival con sus cosas. */
    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        if (game.isParticipant(p.getUniqueId()) || !players.hasSnapshot(p.getUniqueId())) return;
        e.setRespawnLocation(game.fallbackLocation());
        Bukkit.getScheduler().runTask(plugin, () -> players.restore(p));
    }
}
