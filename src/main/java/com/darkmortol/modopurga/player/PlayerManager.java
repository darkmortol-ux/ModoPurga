package com.darkmortol.modopurga.player;

import com.darkmortol.modopurga.ModoPurgaPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Se encarga del jugador: guarda/restaura su estado de Survival (inventario, vida...)
 * y maneja la Brujula Rastreadora. El estado se guarda en snapshots.yml para que
 * no se pierda aunque el servidor se caiga.
 */
public class PlayerManager {

    private final ModoPurgaPlugin plugin;
    private final File file;
    private final YamlConfiguration data;
    private final NamespacedKey trackerKey;

    public PlayerManager(ModoPurgaPlugin plugin) {
        this.plugin = plugin;
        this.trackerKey = new NamespacedKey(plugin, "tracker");
        plugin.getDataFolder().mkdirs();
        this.file = new File(plugin.getDataFolder(), "snapshots.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
    }

    // ---------------- Guardar / restaurar ----------------

    public boolean hasSnapshot(UUID id) {
        return data.contains(id.toString());
    }

    /** Guarda el estado actual. Si ya hay uno guardado NO lo pisa (seria el inventario de la Purga). */
    public void saveSnapshot(Player p) {
        String k = p.getUniqueId().toString();
        if (data.contains(k)) return;
        PlayerInventory inv = p.getInventory();
        data.set(k + ".contents", Arrays.asList(inv.getStorageContents()));
        data.set(k + ".armor", Arrays.asList(inv.getArmorContents()));
        data.set(k + ".offhand", inv.getItemInOffHand());
        data.set(k + ".health", p.getHealth());
        data.set(k + ".food", p.getFoodLevel());
        data.set(k + ".level", p.getLevel());
        data.set(k + ".exp", (double) p.getExp());
        data.set(k + ".gamemode", p.getGameMode().name());
        persist();
    }

    /** Devuelve al jugador su estado de Survival y borra el snapshot. */
    public void restore(Player p) {
        String k = p.getUniqueId().toString();
        if (!data.contains(k)) return;

        PlayerInventory inv = p.getInventory();
        inv.clear();
        inv.setStorageContents(toArray(data.getList(k + ".contents"), 36));
        inv.setArmorContents(toArray(data.getList(k + ".armor"), 4));
        ItemStack off = data.getItemStack(k + ".offhand");
        inv.setItemInOffHand(off == null ? new ItemStack(Material.AIR) : off);

        clearEffects(p);
        p.setHealth(Math.min(20.0, Math.max(1.0, data.getDouble(k + ".health", 20.0))));
        p.setFoodLevel(data.getInt(k + ".food", 20));
        p.setLevel(data.getInt(k + ".level", 0));
        p.setExp((float) data.getDouble(k + ".exp", 0.0));
        try {
            p.setGameMode(GameMode.valueOf(data.getString(k + ".gamemode", "SURVIVAL")));
        } catch (IllegalArgumentException ex) {
            p.setGameMode(GameMode.SURVIVAL);
        }
        p.setFireTicks(0);

        data.set(k, null);
        persist();
    }

    /** Deja al jugador "limpio" para empezar la Purga. */
    public void prepareForEvent(Player p) {
        p.getInventory().clear();
        clearEffects(p);
        p.setGameMode(GameMode.SURVIVAL);
        p.setHealth(20.0);
        p.setFoodLevel(20);
        p.setSaturation(20f);
        p.setLevel(0);
        p.setExp(0f);
        p.setFireTicks(0);
        p.setFallDistance(0f);
    }

    private void clearEffects(Player p) {
        p.getActivePotionEffects().forEach(e -> p.removePotionEffect(e.getType()));
    }

    private ItemStack[] toArray(List<?> list, int size) {
        ItemStack[] arr = new ItemStack[size];
        if (list == null) return arr;
        for (int i = 0; i < Math.min(size, list.size()); i++) {
            Object o = list.get(i);
            arr[i] = (o instanceof ItemStack s) ? s : null;
        }
        return arr;
    }

    private void persist() {
        try {
            data.save(file);
        } catch (IOException ex) {
            plugin.getLogger().severe("No se pudo guardar snapshots.yml: " + ex.getMessage());
        }
    }

    // ---------------- Brujula rastreadora ----------------

    public void giveTracker(Player p) {
        ItemStack compass = new ItemStack(Material.COMPASS);
        ItemMeta meta = compass.getItemMeta();
        meta.displayName(Component.text("Tracker Compass", NamedTextColor.RED));
        meta.getPersistentDataContainer().set(trackerKey, PersistentDataType.BYTE, (byte) 1);
        compass.setItemMeta(meta);
        p.getInventory().addItem(compass);
    }

    public boolean isTracker(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(trackerKey, PersistentDataType.BYTE);
    }

    private boolean hasTracker(Player p) {
        for (ItemStack it : p.getInventory().getContents()) {
            if (isTracker(it)) return true;
        }
        return false;
    }

    /** Apunta la brujula de cada jugador vivo hacia el rival mas cercano. */
    public void updateTrackers(List<Player> alive) {
        for (Player p : alive) {
            Player nearest = null;
            double best = Double.MAX_VALUE;
            for (Player o : alive) {
                if (o.equals(p) || !o.getWorld().equals(p.getWorld())) continue;
                double d = o.getLocation().distanceSquared(p.getLocation());
                if (d < best) {
                    best = d;
                    nearest = o;
                }
            }
            if (nearest == null) continue;
            p.setCompassTarget(nearest.getLocation());
            if (hasTracker(p)) {
                p.sendActionBar(Component.text("Rastreando: " + nearest.getName()
                        + " (" + (int) Math.sqrt(best) + "m)", NamedTextColor.RED));
            }
        }
    }
}
