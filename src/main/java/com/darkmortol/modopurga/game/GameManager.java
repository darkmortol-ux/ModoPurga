package com.darkmortol.modopurga.game;

import com.darkmortol.modopurga.ModoPurgaPlugin;
import com.darkmortol.modopurga.config.ConfigManager;
import com.darkmortol.modopurga.player.PlayerManager;
import com.darkmortol.modopurga.world.WorldManager;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Cerebro de la partida: estados, temporizador, BossBar, victoria y salida de jugadores.
 * Un solo reloj (tick cada 1 segundo) maneja todas las fases.
 */
public class GameManager {

    private static final String BYPASS = "modopurga.bypass";

    private final ModoPurgaPlugin plugin;
    private final ConfigManager cfg;
    private final WorldManager worlds;
    private final PlayerManager players;

    private GameState state = GameState.WAITING;
    private final Set<UUID> participants = new LinkedHashSet<>();
    private boolean pvpActive = false;
    private int seconds;   // segundos restantes de la fase actual
    private int total;     // duracion total de la fase (para la barra)
    private int countdown; // cuenta regresiva previa al PvP

    private final BossBar bar = BossBar.bossBar(Component.empty(), 1f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
    private BukkitTask tickTask;
    private BukkitTask endTask;

    public GameManager(ModoPurgaPlugin plugin, ConfigManager cfg, WorldManager worlds, PlayerManager players) {
        this.plugin = plugin;
        this.cfg = cfg;
        this.worlds = worlds;
        this.players = players;
    }

    // ---------------- Consultas ----------------

    public boolean isEnabled() { return cfg.isEnabled(); }
    public boolean isEventWorld(World w) { return w != null && w.getName().equals(cfg.eventWorld()); }
    public boolean isParticipant(UUID id) { return participants.contains(id); }
    public boolean isPvpActive() { return pvpActive; }
    public GameState getState() { return state; }
    public int participantCount() { return participants.size(); }

    public String timeLeft() {
        return (state == GameState.LOOTING || state == GameState.PVP) ? format(seconds) : "-";
    }

    public Location fallbackLocation() {
        World w = Bukkit.getWorld(cfg.fallbackWorld());
        if (w == null) w = Bukkit.getWorlds().get(0);
        return w.getSpawnLocation();
    }

    public void sendToFallback(Player p) {
        p.teleport(fallbackLocation());
    }

    /** Se llama al iniciar el plugin: deja el borde listo. */
    public void prepare() {
        if (worlds.getWorld() == null) {
            plugin.getLogger().warning("El mundo '" + cfg.eventWorld() + "' no esta cargado.");
            return;
        }
        worlds.applyBorder(cfg.borderLooting());
    }

    // ---------------- Entrada y salida de jugadores ----------------

    /** Un jugador llego al mundo del evento. */
    public void join(Player p) {
        if (!isEnabled() || p.hasPermission(BYPASS)) return;
        if (participants.contains(p.getUniqueId())) return;
        if (state == GameState.PVP || state == GameState.ENDING) {
            p.sendMessage(cfg.msg("game-in-progress"));
            sendToFallback(p);
            return;
        }
        players.saveSnapshot(p);      // 1) guardar Survival
        players.prepareForEvent(p);   // 2) limpiar para la Purga
        participants.add(p.getUniqueId());
        p.showBossBar(bar);
        if (state == GameState.WAITING) {
            worlds.applyBorder(cfg.borderLooting());
            bar.name(cfg.msg("bossbar-waiting"));
            bar.progress(1f);
        }
        Location spot = worlds.randomLocation(cfg.borderLooting());
        Bukkit.getScheduler().runTask(plugin, () -> p.teleport(spot));
        p.sendMessage(cfg.msg("joined"));
        broadcastToParticipants(cfg.msg("player-joined", "player", p.getName(), "count", String.valueOf(participants.size())));
    }

    /** /purga salir o salida por otro medio. teleport=false si el jugador ya se fue del mundo. */
    public void leave(Player p, boolean teleport) {
        if (!participants.remove(p.getUniqueId())) return;
        p.hideBossBar(bar);
        if (teleport) sendToFallback(p);
        players.restore(p);
        p.sendMessage(cfg.msg("left"));
        broadcastToParticipants(cfg.msg("player-left", "player", p.getName(), "count", String.valueOf(participants.size())));
        checkWinner();
    }

    /** El jugador murio: sale de la partida (su estado se restaura al reaparecer). */
    public void eliminate(Player p) {
        if (!participants.remove(p.getUniqueId())) return;
        p.hideBossBar(bar);
        broadcastToParticipants(cfg.msg("player-eliminated", "player", p.getName(), "count", String.valueOf(participants.size())));
        checkWinner();
    }

    /** El jugador se desconecto: su estado se restaura cuando vuelva a entrar. */
    public void disconnect(Player p) {
        if (!participants.remove(p.getUniqueId())) return;
        p.hideBossBar(bar);
        broadcastToParticipants(cfg.msg("player-left", "player", p.getName(), "count", String.valueOf(participants.size())));
        checkWinner();
    }

    // ---------------- Fases ----------------

    /** Inicia la partida. Devuelve la clave del mensaje de error, o null si salio bien. */
    public String start() {
        if (!isEnabled()) return "disabled";
        if (worlds.getWorld() == null) return "world-missing";
        if (state != GameState.WAITING) return "already-running";
        if (participants.size() < cfg.minPlayers()) return "not-enough-players";

        state = GameState.LOOTING;
        pvpActive = false;
        seconds = total = cfg.lootingSeconds();
        worlds.applyBorder(cfg.borderLooting());
        worlds.spawnLoot();
        broadcastToParticipants(cfg.msg("game-started"));
        refreshBar();
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
        return null;
    }

    /** Se ejecuta cada segundo. */
    private void tick() {
        if (state == GameState.LOOTING) {
            seconds--;
            if (seconds <= 0) {
                startPvp();
                return;
            }
            refreshBar();
        } else if (state == GameState.PVP) {
            if (countdown > 0) {
                countdown--;
                if (countdown > 0) {
                    Title t = Title.title(cfg.msg("countdown-title", "time", String.valueOf(countdown)), Component.empty());
                    for (Player p : onlineParticipants()) p.showTitle(t);
                } else {
                    pvpActive = true;
                    broadcastToParticipants(cfg.msg("pvp-started"));
                }
            } else {
                seconds--;
                if (seconds <= 0) {
                    endGame(null); // se acabo el tiempo
                    return;
                }
            }
            refreshBar();
            players.updateTrackers(onlineParticipants());
        }
    }

    private void startPvp() {
        state = GameState.PVP;
        pvpActive = false;
        countdown = cfg.pvpCountdown();
        seconds = total = cfg.pvpMaxSeconds();
        worlds.applyBorder(cfg.borderPvp()); // primero el borde...
        for (Player p : onlineParticipants()) {
            p.teleport(worlds.randomLocation(cfg.borderPvp())); // ...luego la teletransportacion
            players.giveTracker(p);
        }
        broadcastToParticipants(cfg.msg("pvp-phase"));
        refreshBar();
    }

    /** Revisa si quedo un unico jugador vivo. */
    private void checkWinner() {
        if (state != GameState.LOOTING && state != GameState.PVP) return;
        if (participants.size() > 1) return;
        Player winner = participants.isEmpty() ? null : Bukkit.getPlayer(participants.iterator().next());
        endGame(winner);
    }

    private void endGame(Player winner) {
        if (state != GameState.LOOTING && state != GameState.PVP) return;
        state = GameState.ENDING;
        pvpActive = false;
        if (tickTask != null) tickTask.cancel();

        if (winner != null) {
            Bukkit.broadcast(cfg.msg("victory-broadcast", "winner", winner.getName())); // a todo el servidor
            winner.sendMessage(cfg.msg("winner-reward-claim"));                         // privado
        } else {
            Bukkit.broadcast(cfg.msg("no-winner-broadcast"));
        }
        bar.name(cfg.msg("bossbar-ending"));
        bar.progress(1f);
        bar.color(BossBar.Color.GREEN);
        endTask = Bukkit.getScheduler().runTaskLater(plugin, () -> finish(true), 20L * cfg.endDelaySeconds());
    }

    /** Devuelve a todos al Survival y (opcionalmente) regenera el mundo. */
    private void finish(boolean resetWorld) {
        if (tickTask != null) tickTask.cancel();
        if (endTask != null) endTask.cancel();
        worlds.cancelLoot();
        for (UUID id : new ArrayList<>(participants)) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            p.hideBossBar(bar);
            sendToFallback(p);
            players.restore(p);
        }
        participants.clear();
        state = GameState.WAITING;
        pvpActive = false;
        if (resetWorld) Bukkit.getScheduler().runTaskLater(plugin, worlds::reset, 20L);
    }

    /** /purga stop: termina ya, sin ganador. */
    public void forceStop() {
        boolean wasRunning = state != GameState.WAITING;
        finish(wasRunning);
    }

    /** Apagado del servidor/plugin: devuelve a los jugadores sin programar tareas. */
    public void shutdown() {
        if (tickTask != null) tickTask.cancel();
        if (endTask != null) endTask.cancel();
        worlds.cancelLoot();
        for (UUID id : new ArrayList<>(participants)) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            p.hideBossBar(bar);
            sendToFallback(p);
            players.restore(p);
        }
        participants.clear();
    }

    // ---------------- Utilidades ----------------

    private void refreshBar() {
        if (state == GameState.LOOTING) {
            bar.name(cfg.msg("bossbar-looting", "time", format(seconds)));
            bar.color(BossBar.Color.YELLOW);
            bar.progress(progress());
        } else if (state == GameState.PVP) {
            if (countdown > 0) {
                bar.name(cfg.msg("bossbar-countdown", "time", String.valueOf(countdown)));
                bar.progress(1f);
            } else {
                bar.name(cfg.msg("bossbar-pvp", "time", format(seconds)));
                bar.progress(progress());
            }
            bar.color(BossBar.Color.RED);
        }
    }

    private float progress() {
        return Math.max(0f, Math.min(1f, total <= 0 ? 0f : (float) seconds / total));
    }

    private String format(int s) {
        return String.format("%02d:%02d", s / 60, s % 60);
    }

    private List<Player> onlineParticipants() {
        List<Player> list = new ArrayList<>();
        for (UUID id : participants) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) list.add(p);
        }
        return list;
    }

    private void broadcastToParticipants(Component c) {
        for (Player p : onlineParticipants()) p.sendMessage(c);
    }
}
