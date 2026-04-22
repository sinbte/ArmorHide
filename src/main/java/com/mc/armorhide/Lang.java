package com.mc.armorhide;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Static message lookup backed by a language file (lang/en.yml).
 * Missing keys fall back to the bundled English defaults, then to the key itself.
 */
public final class Lang {

    private static FileConfiguration cfg;

    private Lang() {}

    public static void init(JavaPlugin plugin) {
        String language = plugin.getConfig().getString("language", "en");
        File langDir = new File(plugin.getDataFolder(), "lang");
        if (!langDir.exists()) langDir.mkdirs();
        if (!new File(langDir, "en.yml").exists()) {
            plugin.saveResource("lang/en.yml", false);
        }
        File chosen = new File(langDir, language + ".yml");
        if (!chosen.exists()) {
            plugin.getLogger().warning("Language '" + language + "' not found, falling back to en");
            chosen = new File(langDir, "en.yml");
        }
        cfg = YamlConfiguration.loadConfiguration(chosen);
        InputStream en = plugin.getResource("lang/en.yml");
        if (en != null) {
            cfg.setDefaults(YamlConfiguration.loadConfiguration(
                new InputStreamReader(en, StandardCharsets.UTF_8)));
        }
    }

    public static String get(Player player, String key) {
        return cfg != null ? cfg.getString(key, key) : key;
    }

    public static String get(Player player, String key, Object... args) {
        String s = get(player, key);
        for (int i = 0; i + 1 < args.length; i += 2) {
            s = s.replace("%" + args[i] + "%", String.valueOf(args[i + 1]));
        }
        return s;
    }

    public static void send(Player player, String key) {
        if (player != null) player.sendMessage(get(player, key));
    }

    public static void send(Player player, String key, Object... args) {
        if (player != null) player.sendMessage(get(player, key, args));
    }
}
