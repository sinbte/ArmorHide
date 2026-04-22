package com.mc.armorhide;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

/**
 * Local YAML persistence for per-player settings, replacing the shared MySQL table.
 * Stored in playerdata.yml: armor-hidden (boolean) and cosmetic-hat (Base64 item).
 */
public final class Storage {

    private final JavaPlugin plugin;
    private final File file;
    private final FileConfiguration cfg;

    public Storage(JavaPlugin plugin) {
        this.plugin = plugin;
        if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
        this.file = new File(plugin.getDataFolder(), "playerdata.yml");
        this.cfg = YamlConfiguration.loadConfiguration(file);
    }

    public boolean isArmorHidden(UUID uuid) {
        return cfg.getBoolean("players." + uuid + ".armor-hidden", false);
    }

    public String getCosmeticHat(UUID uuid) {
        return cfg.getString("players." + uuid + ".cosmetic-hat", null);
    }

    public void setArmorHidden(UUID uuid, boolean hidden) {
        cfg.set("players." + uuid + ".armor-hidden", hidden);
        saveAsync();
    }

    public void setCosmeticHat(UUID uuid, String base64) {
        cfg.set("players." + uuid + ".cosmetic-hat", base64);
        saveAsync();
    }

    private void saveAsync() {
        Sched.async(plugin, () -> {
            synchronized (this) {
                try {
                    cfg.save(file);
                } catch (IOException e) {
                    plugin.getLogger().warning("Failed to save playerdata.yml: " + e.getMessage());
                }
            }
        });
    }
}
