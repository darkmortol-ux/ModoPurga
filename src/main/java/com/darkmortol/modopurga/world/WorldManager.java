package com.darkmortol.modopurga.world;

import com.darkmortol.modopurga.ModoPurgaPlugin;
import com.darkmortol.modopurga.config.ConfigManager;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Random;
import java.util.stream.Stream;

/** Todo lo que toca el mundo: bordes, loot en el suelo, estructuras con cofres y reseteo. */
public class WorldManager {

    private final ModoPurgaPlugin plugin;
    private final ConfigManager cfg;
    private final Random random = new Random();
    private BukkitTask lootTask;

    public WorldManager(ModoPurgaPlugin plugin, ConfigManager cfg) {
        this.plugin = plugin;
        this.cfg = cfg;
    }

    /** El mundo del evento (null si no existe o no esta cargado). */
    public World getWorld() {
        return Bukkit.getWorld(cfg.eventWorld());
    }

    // ---------------- Borde ----------------

    public void applyBorder(int size) {
        World w = getWorld();
        if (w == null) return;
        WorldBorder b = w.getWorldBorder();
        b.setCenter(0, 0);
        b.setSize(size);
    }

    // ---------------- Ubicaciones ----------------

    /** Lugar aleatorio y seguro (no agua/lava) dentro de un borde de tamaño "borderSize". */
    public Location randomLocation(int borderSize) {
        World w = getWorld();
        int half = Math.max(1, borderSize / 2 - 5);
        for (int i = 0; i < 15; i++) {
            int x = random.nextInt(half * 2 + 1) - half;
            int z = random.nextInt(half * 2 + 1) - half;
            int y = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            Block ground = w.getBlockAt(x, y, z);
            if (ground.isLiquid() || ground.getType() == Material.LAVA) continue;
            return new Location(w, x + 0.5, y + 1, z + 0.5);
        }
        return w.getSpawnLocation(); // plan B
    }

    // ---------------- Loot ----------------

    /** Genera estructuras con cofres e items sueltos, de a pocos por tick para no lagear. */
    public void spawnLoot() {
        cancelLoot();
        if (getWorld() == null) return;
        int size = cfg.borderLooting();
        final int[] structures = {cfg.structureCount()};
        final int[] items = {cfg.groundItemCount()};

        lootTask = new BukkitRunnable() {
            @Override
            public void run() {
                int ops = 0;
                while (ops < 6 && (structures[0] > 0 || items[0] > 0)) {
                    if (structures[0] > 0) {
                        buildStructure(randomLocation(size));
                        structures[0]--;
                    } else {
                        dropGroundItem(randomLocation(size));
                        items[0]--;
                    }
                    ops++;
                }
                if (structures[0] <= 0 && items[0] <= 0) cancel();
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    public void cancelLoot() {
        if (lootTask != null) {
            lootTask.cancel();
            lootTask = null;
        }
    }

    /** Item suelto flotando en el suelo (NO bloque colocado). */
    private void dropGroundItem(Location loc) {
        ItemStack stack = cfg.randomGroundItem();
        if (stack == null) return;
        Item item = loc.getWorld().dropItem(loc.clone().add(0, 0.3, 0), stack);
        item.setVelocity(new Vector(0, 0, 0));
        item.setPickupDelay(0);
        item.setUnlimitedLifetime(true); // que no desaparezca a los 5 minutos
    }

    /** Estructuras simples: pilar, piramide o monticulo, con un cofre arriba. */
    private void buildStructure(Location base) {
        World w = base.getWorld();
        int bx = base.getBlockX(), by = base.getBlockY(), bz = base.getBlockZ();
        Location chestLoc;
        switch (random.nextInt(3)) {
            case 0 -> { // pilar de 5 bloques
                for (int i = 0; i < 5; i++) w.getBlockAt(bx, by + i, bz).setType(Material.STONE_BRICKS);
                chestLoc = new Location(w, bx, by + 5, bz);
            }
            case 1 -> { // piramide de 3 pisos
                for (int i = 0; i < 3; i++) {
                    int r = 2 - i;
                    for (int dx = -r; dx <= r; dx++)
                        for (int dz = -r; dz <= r; dz++)
                            w.getBlockAt(bx + dx, by + i, bz + dz).setType(Material.COBBLESTONE);
                }
                chestLoc = new Location(w, bx, by + 3, bz);
            }
            default -> { // monticulo de tierra (media esfera, radio 3)
                for (int dx = -3; dx <= 3; dx++)
                    for (int dy = 0; dy <= 3; dy++)
                        for (int dz = -3; dz <= 3; dz++)
                            if (dx * dx + dy * dy + dz * dz <= 9)
                                w.getBlockAt(bx + dx, by + dy, bz + dz).setType(Material.DIRT);
                chestLoc = new Location(w, bx, by + 4, bz);
            }
        }
        placeChest(chestLoc);
    }

    private void placeChest(Location loc) {
        Block block = loc.getBlock();
        block.setType(Material.CHEST);
        Chest chest = (Chest) block.getState();
        Inventory inv = chest.getBlockInventory();
        int amount = 3 + random.nextInt(4); // 3 a 6 items
        for (int i = 0; i < amount; i++) {
            ItemStack it = cfg.randomChestItem();
            if (it != null) inv.setItem(random.nextInt(inv.getSize()), it);
        }
    }

    // ---------------- Reset ----------------

    /** Regenera el mundo. Antes de llamar esto los jugadores ya deben estar fuera. */
    public void reset() {
        World w = getWorld();
        if (w == null) return;
        String name = w.getName();
        cancelLoot();

        // Por seguridad, sacamos a cualquiera que quede dentro.
        World fallback = Bukkit.getWorld(cfg.fallbackWorld());
        if (fallback == null) fallback = Bukkit.getWorlds().get(0);
        for (Player p : new ArrayList<>(w.getPlayers())) p.teleport(fallback.getSpawnLocation());

        if ("multiverse".equalsIgnoreCase(cfg.resetMethod())) {
            String cmd = cfg.mvRegenCommand().replace("{world}", name);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            plugin.getLogger().info("Reset via Multiverse: /" + cmd);
            return;
        }

        World.Environment env = w.getEnvironment();
        File folder = w.getWorldFolder();
        if (!Bukkit.unloadWorld(w, false)) {
            plugin.getLogger().warning("No se pudo descargar el mundo " + name + "; no se reseteo.");
            return;
        }
        deleteRecursively(folder);
        new WorldCreator(name).environment(env).seed(random.nextLong()).createWorld();
        plugin.getLogger().info("Mundo " + name + " regenerado con nueva seed.");
    }

    private void deleteRecursively(File folder) {
        try (Stream<Path> s = Files.walk(folder.toPath())) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ex) {
                    plugin.getLogger().warning("No se pudo borrar " + p + ": " + ex.getMessage());
                }
            });
        } catch (IOException ex) {
            plugin.getLogger().warning("Error borrando el mundo: " + ex.getMessage());
        }
    }
}
