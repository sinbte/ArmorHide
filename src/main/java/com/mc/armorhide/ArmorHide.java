package com.mc.armorhide;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * ArmorHide
 * Hide your armor from other players (/hidearmor) and wear any item as a
 * cosmetic hat visible to others (/cosmetichat). Requires ProtocolLib.
 */
public class ArmorHide extends JavaPlugin {

    private ArmorHideListener listener;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Lang.init(this);

        var protocolLib = getServer().getPluginManager().getPlugin("ProtocolLib");
        if (protocolLib == null || !protocolLib.isEnabled()) {
            getLogger().severe("ProtocolLib is missing or failed to enable (incompatible server version?) — disabling ArmorHide.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        Storage storage = new Storage(this);
        listener = new ArmorHideListener(this, storage);
        getServer().getPluginManager().registerEvents(listener, this);
        getCommand("hidearmor").setExecutor(listener);
        getCommand("cosmetichat").setExecutor(listener);
        listener.registerPacketListener();

        getLogger().info("ArmorHide enabled - /hidearmor, /cosmetichat (ProtocolLib)");
    }

    @Override
    public void onDisable() {
        if (listener != null) listener.unregisterPacketListener();
    }
}
