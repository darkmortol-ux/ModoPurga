package com.darkmortol.modopurga.config;

import com.darkmortol.modopurga.ModoPurgaPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** Lee config.yml y ofrece metodos simples (getters, mensajes y loot ya procesado). */
public class ConfigManager {

    /** Una linea de loot: material + rango de cantidad. */
    public record LootEntry(Material material, int min, int max) {
        public ItemStack roll() {
            int amount = min >= max ? min : ThreadLocalRandom.current().nextInt(min, max + 1);
            return new ItemStack(material, Math.max(1, amount));
        }
    }

    private final ModoPurgaPlugin plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();
    private List<LootEntry> groundLoot = List.of();
    private List<LootEntry> chestLoot = List.of();
    private List<String> whitelist = List.of();

    public ConfigManager(ModoPurgaPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    /** Recarga config.yml desde disco. */
    public void reload() {
        plugin.reloadConfig();
        load();
    }

    private FileConfiguration c() {
        return plugin.getConfig();
    }

    private void load() {
        groundLoot = parseLoot(c().getStringList("loot.ground-items"));
        chestLoot = parseLoot(c().getStringList("loot.chest-items"));
        List<String> wl = new ArrayList<>();
        for (String s : c().getStringList("whitelisted-commands")) {
            wl.add(s.trim().toLowerCase().replaceAll("\\s+", " "));
        }
        whitelist = wl;
    }

    /** Convierte "IRON_SWORD:1" o "ARROW:8-16" en LootEntry. */
    private List<LootEntry> parseLoot(List<String> lines) {
        List<LootEntry> out = new ArrayList<>();
        for (String line : lines) {
            String[] parts = line.split(":");
            Material m = Material.matchMaterial(parts[0].trim());
            if (m == null || !m.isItem()) {
                plugin.getLogger().warning("Item de loot invalido: " + line);
                continue;
            }
            int min = 1, max = 1;
            if (parts.length > 1) {
                try {
                    String a = parts[1].trim();
                    if (a.contains("-")) {
                        String[] r = a.split("-");
                        min = Integer.parseInt(r[0].trim());
                        max = Integer.parseInt(r[1].trim());
                    } else {
                        min = max = Integer.parseInt(a);
                    }
                } catch (NumberFormatException ex) {
                    plugin.getLogger().warning("Cantidad invalida en loot: " + line);
                }
            }
            out.add(new LootEntry(m, min, max));
        }
        return out;
    }

    // ---------- Valores simples ----------
    public boolean isEnabled() { return c().getBoolean("enabled", false); }
    public String eventWorld() { return c().getString("event-world", ""); }
    public String fallbackWorld() { return c().getString("survival-fallback-world", "world"); }
    public int borderLooting() { return c().getInt("border-size-looting", 500); }
    public int borderPvp() { return c().getInt("border-size-pvp", 50); }
    public int lootingSeconds() { return c().getInt("looting-time-seconds", 600); }
    public int pvpCountdown() { return c().getInt("pvp-countdown-seconds", 10); }
    public int pvpMaxSeconds() { return c().getInt("pvp-max-seconds", 600); }
    public int endDelaySeconds() { return c().getInt("end-delay-seconds", 10); }
    public int minPlayers() { return c().getInt("min-players", 2); }
    public String resetMethod() { return c().getString("reset-method", "bukkit"); }
    public String mvRegenCommand() { return c().getString("multiverse-regen-command", "mv regen {world} -s"); }
    public int groundItemCount() { return c().getInt("loot.ground-items-count", 150); }
    public int structureCount() { return c().getInt("loot.structures-count", 12); }
    public List<String> whitelistedCommands() { return whitelist; }

    // ---------- Loot ----------
    public ItemStack randomGroundItem() {
        if (groundLoot.isEmpty()) return null;
        return groundLoot.get(ThreadLocalRandom.current().nextInt(groundLoot.size())).roll();
    }

    public ItemStack randomChestItem() {
        if (chestLoot.isEmpty()) return null;
        return chestLoot.get(ThreadLocalRandom.current().nextInt(chestLoot.size())).roll();
    }

    // ---------- Mensajes ----------
    /** Texto crudo con placeholders reemplazados. pairs = clave, valor, clave, valor... */
    public String raw(String key, String... pairs) {
        String text = c().getString("messages." + key, "<red>[Falta el mensaje: " + key + "]");
        text = text.replace("{prefix}", c().getString("messages.prefix", ""));
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            text = text.replace("{" + pairs[i] + "}", pairs[i + 1]);
        }
        return text;
    }

    /** Mensaje listo para enviar (MiniMessage -> Component). */
    public Component msg(String key, String... pairs) {
        return mm.deserialize(raw(key, pairs));
    }
}
