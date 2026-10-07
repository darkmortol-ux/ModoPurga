package com.darkmortol.modopurga;

import com.darkmortol.modopurga.command.PurgaCommand;
import com.darkmortol.modopurga.config.ConfigManager;
import com.darkmortol.modopurga.game.GameManager;
import com.darkmortol.modopurga.listener.IsolationManager;
import com.darkmortol.modopurga.listener.PlayerListener;
import com.darkmortol.modopurga.player.PlayerManager;
import com.darkmortol.modopurga.world.WorldManager;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Clase principal. Solo "arma" las piezas: crea los managers, registra
 * listeners y el comando /purga. La logica vive en cada manager.
 */
public class ModoPurgaPlugin extends JavaPlugin {

    private GameManager gameManager;

    @Override
    public void onEnable() {
        saveDefaultConfig(); // crea config.yml la primera vez

        ConfigManager config = new ConfigManager(this);
        WorldManager worlds = new WorldManager(this, config);
        PlayerManager players = new PlayerManager(this);
        gameManager = new GameManager(this, config, worlds, players);

        // Los listeners siempre estan registrados, pero cada uno revisa "enabled" antes de actuar.
        getServer().getPluginManager().registerEvents(new IsolationManager(config, gameManager), this);
        getServer().getPluginManager().registerEvents(new PlayerListener(this, gameManager, players), this);

        PurgaCommand command = new PurgaCommand(config, gameManager);
        getCommand("purga").setExecutor(command);
        getCommand("purga").setTabCompleter(command);

        if (!config.isEnabled()) {
            getLogger().warning("ModoPurga esta DESACTIVADO (enabled: false en config.yml).");
        } else {
            // Esperamos 2 segundos para que Multiverse termine de cargar los mundos.
            getServer().getScheduler().runTaskLater(this, gameManager::prepare, 40L);
        }
    }

    @Override
    public void onDisable() {
        if (gameManager != null) {
            gameManager.shutdown(); // devuelve a los jugadores y sus inventarios
        }
    }
}
