package com.darkmortol.modopurga.listener;

import com.darkmortol.modopurga.config.ConfigManager;
import com.darkmortol.modopurga.game.GameManager;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.world.PortalCreateEvent;

/**
 * Aislamiento: dentro del mundo del evento bloquea comandos de otros plugins,
 * portales (Nether/End) y el PvP cuando no toca.
 */
public class IsolationManager implements Listener {

    private final ConfigManager cfg;
    private final GameManager game;

    public IsolationManager(ConfigManager cfg, GameManager game) {
        this.cfg = cfg;
        this.game = game;
    }

    /** Bloquea TODO comando que no este en la lista blanca. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (!game.isEnabled() || !game.isEventWorld(p.getWorld())) return;
        if (p.hasPermission("modopurga.bypass")) return;

        String line = normalize(e.getMessage());
        for (String allowed : cfg.whitelistedCommands()) {
            if (line.equals(allowed) || line.startsWith(allowed + " ")) return; // permitido
        }
        e.setCancelled(true);
        p.sendMessage(cfg.msg("command-blocked"));
    }

    /** "/Plugin:Cmd  Arg" -> "/cmd arg" (quita el prefijo plugin: para que no se cuele nada). */
    private String normalize(String message) {
        String m = message.trim().toLowerCase().replaceAll("\\s+", " ");
        if (m.startsWith("/")) m = m.substring(1);
        String[] parts = m.split(" ", 2);
        String label = parts[0];
        int colon = label.indexOf(':');
        if (colon >= 0) label = label.substring(colon + 1);
        return "/" + label + (parts.length > 1 ? " " + parts[1] : "");
    }

    // ---------------- Portales ----------------

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerPortal(PlayerPortalEvent e) {
        if (game.isEnabled() && game.isEventWorld(e.getFrom().getWorld())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityPortal(EntityPortalEvent e) {
        if (game.isEnabled() && game.isEventWorld(e.getFrom().getWorld())) e.setCancelled(true);
    }

    /** Tampoco se pueden crear portales (encender obsidiana, etc.). */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPortalCreate(PortalCreateEvent e) {
        if (game.isEnabled() && game.isEventWorld(e.getWorld())) e.setCancelled(true);
    }

    // ---------------- PvP ----------------

    /** Jugador contra jugador (tambien flechas, etc.) solo cuando el PvP esta activo. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        if (!game.isEnabled() || !game.isEventWorld(victim.getWorld())) return;

        Player attacker = null;
        Entity damager = e.getDamager();
        if (damager instanceof Player a) {
            attacker = a;
        } else if (damager instanceof Projectile pr && pr.getShooter() instanceof Player a2) {
            attacker = a2;
        }
        if (attacker == null) return; // mobs y entorno SI hacen daño

        if (!game.isPvpActive()) {
            e.setCancelled(true);
            attacker.sendActionBar(cfg.msg("pvp-disabled"));
        }
    }
}
