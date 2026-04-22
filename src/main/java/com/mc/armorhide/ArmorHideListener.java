package com.mc.armorhide;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.EnumWrappers;
import com.comphenix.protocol.wrappers.Pair;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Armor hiding + cosmetic hat.
 *
 * - ENTITY_EQUIPMENT: hides armor from everyone (including the player's own F5 view)
 * - Inventory: armor slots show a lime glass pane whose hover text keeps the original stats
 * - Removing the armor reveals the real item; putting it back shows the placeholder again
 */
public class ArmorHideListener implements Listener, CommandExecutor {

    private final ArmorHide plugin;
    private final Storage storage;
    private final Set<UUID> hiddenArmor = ConcurrentHashMap.newKeySet();
    private final Map<UUID, ItemStack> cosmeticHats = new ConcurrentHashMap<>();
    private final Map<UUID, ItemStack> playerHeadCache = new ConcurrentHashMap<>();
    private final ProtocolManager protocolManager;
    private ScheduledTask refreshTask;

    // Armor equipment slots
    private static final Set<EnumWrappers.ItemSlot> ALL_ARMOR_SLOTS = Set.of(
        EnumWrappers.ItemSlot.HEAD,
        EnumWrappers.ItemSlot.CHEST,
        EnumWrappers.ItemSlot.LEGS,
        EnumWrappers.ItemSlot.FEET
    );

    // Armor slot numbers in the player inventory window
    private static final int HELMET_SLOT = 5;
    private static final int CHESTPLATE_SLOT = 6;
    private static final int LEGGINGS_SLOT = 7;
    private static final int BOOTS_SLOT = 8;
    private static final Set<Integer> ARMOR_INVENTORY_SLOTS = Set.of(
        HELMET_SLOT, CHESTPLATE_SLOT, LEGGINGS_SLOT, BOOTS_SLOT
    );

    public ArmorHideListener(ArmorHide plugin, Storage storage) {
        this.plugin = plugin;
        this.storage = storage;
        this.protocolManager = ProtocolLibrary.getProtocolManager();
    }

    /**
     * Periodically refresh equipment display (every 20 ticks = 1 second).
     */
    private void startPeriodicRefresh() {
        if (refreshTask != null) {
            refreshTask.cancel();
        }
        refreshTask = Sched.globalTimer(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                UUID uuid = player.getUniqueId();
                if (!hiddenArmor.contains(uuid) && !cosmeticHats.containsKey(uuid)) continue;
                // Jump to each player's region thread for their equipment/inventory refresh
                Sched.entity(plugin, player, () -> {
                    if (hiddenArmor.contains(uuid) || cosmeticHats.containsKey(uuid)) {
                        refreshEquipmentForAll(player);
                    }
                    if (hiddenArmor.contains(uuid)) {
                        player.updateInventory();
                    }
                });
            }
        }, 20L, 20L);
    }

    public void registerPacketListener() {
        startPeriodicRefresh();

        // Reload settings for all online players (e.g. on /reload)
        for (Player player : Bukkit.getOnlinePlayers()) {
            loadSettings(player.getUniqueId());
        }

        // ENTITY_EQUIPMENT - controls what F5 and other players see
        protocolManager.addPacketListener(new PacketAdapter(plugin, ListenerPriority.HIGHEST,
                PacketType.Play.Server.ENTITY_EQUIPMENT) {
            @Override
            public void onPacketSending(PacketEvent event) {
                handleEntityEquipment(event);
            }
        });

        // WINDOW_ITEMS - full item list when the inventory opens
        protocolManager.addPacketListener(new PacketAdapter(plugin, ListenerPriority.HIGHEST,
                PacketType.Play.Server.WINDOW_ITEMS) {
            @Override
            public void onPacketSending(PacketEvent event) {
                handleWindowItems(event);
            }
        });

        // SET_SLOT - single slot update
        protocolManager.addPacketListener(new PacketAdapter(plugin, ListenerPriority.HIGHEST,
                PacketType.Play.Server.SET_SLOT) {
            @Override
            public void onPacketSending(PacketEvent event) {
                handleSetSlot(event);
            }
        });

        // SET_CREATIVE_SLOT - stop creative mode from writing placeholders into the real inventory
        protocolManager.addPacketListener(new PacketAdapter(plugin, ListenerPriority.HIGHEST,
                PacketType.Play.Client.SET_CREATIVE_SLOT) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                handleSetCreativeSlot(event);
            }
        });

        plugin.getLogger().info("ArmorHide: packet listeners registered (inventory glass placeholders enabled)");
    }

    /**
     * Handle ENTITY_EQUIPMENT - controls the entity model display.
     */
    private void handleEntityEquipment(PacketEvent event) {
        if (event.isCancelled()) return;

        PacketContainer packet = event.getPacket();
        int entityId = packet.getIntegers().read(0);

        Player target = null;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getEntityId() == entityId) {
                target = p;
                break;
            }
        }

        if (target == null) return;

        UUID targetUUID = target.getUniqueId();
        boolean hideArmor = hiddenArmor.contains(targetUUID);
        ItemStack cosmeticHat = cosmeticHats.get(targetUUID);

        if (!hideArmor && cosmeticHat == null) return;

        try {
            List<Pair<EnumWrappers.ItemSlot, ItemStack>> equipmentList =
                packet.getSlotStackPairLists().read(0);

            List<Pair<EnumWrappers.ItemSlot, ItemStack>> newList = new ArrayList<>();
            for (Pair<EnumWrappers.ItemSlot, ItemStack> pair : equipmentList) {
                EnumWrappers.ItemSlot slot = pair.getFirst();

                if (slot == EnumWrappers.ItemSlot.HEAD) {
                    // Head: prefer the cosmetic hat, otherwise show the player head
                    if (cosmeticHat != null) {
                        newList.add(new Pair<>(slot, cosmeticHat.clone()));
                    } else if (hideArmor) {
                        newList.add(new Pair<>(slot, getPlayerHead(target)));
                    } else {
                        newList.add(pair);
                    }
                } else if (ALL_ARMOR_SLOTS.contains(slot)) {
                    // Other armor slots: hidden (elytra excluded)
                    ItemStack item = pair.getSecond();
                    if (hideArmor && (item == null || item.getType() != Material.ELYTRA)) {
                        newList.add(new Pair<>(slot, new ItemStack(Material.AIR)));
                    } else {
                        newList.add(pair);
                    }
                } else {
                    newList.add(pair);
                }
            }

            packet.getSlotStackPairLists().write(0, newList);
        } catch (Exception e) {
            // ignore
        }
    }

    /**
     * Handle WINDOW_ITEMS - the full inventory item list.
     */
    private void handleWindowItems(PacketEvent event) {
        if (event.isCancelled()) return;

        Player player = event.getPlayer();
        if (!hiddenArmor.contains(player.getUniqueId())) return;

        // Skip in creative to avoid the client writing fake items back
        if (player.getGameMode() == GameMode.CREATIVE) return;

        PacketContainer packet = event.getPacket();

        // Window ID 0 = player inventory
        int windowId = packet.getIntegers().read(0);
        if (windowId != 0) return;

        try {
            List<ItemStack> items = packet.getItemListModifier().read(0);
            if (items == null || items.isEmpty()) return;

            List<ItemStack> newItems = new ArrayList<>(items);
            boolean modified = false;

            ItemStack cosmeticHat = cosmeticHats.get(player.getUniqueId());

            for (int slot : ARMOR_INVENTORY_SLOTS) {
                if (slot < newItems.size()) {
                    ItemStack original = newItems.get(slot);

                    // Helmet slot: cosmetic hat appearance + real helmet stats, otherwise glass pane
                    if (slot == HELMET_SLOT) {
                        if (cosmeticHat != null) {
                            ItemStack helmet = original != null && isArmor(original.getType()) ? original : null;
                            newItems.set(slot, helmet != null ? createPlaceholder(cosmeticHat, helmet) : cosmeticHat.clone());
                            modified = true;
                        } else if (original != null && original.getType() != Material.AIR && isArmor(original.getType())) {
                            newItems.set(slot, createPlaceholder(getPlayerHead(player), original));
                            modified = true;
                        }
                    } else if (original != null && original.getType() != Material.AIR && isArmor(original.getType())) {
                        newItems.set(slot, createGlassPlaceholder(original, slot));
                        modified = true;
                    }
                }
            }

            if (modified) {
                packet.getItemListModifier().write(0, newItems);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to handle WINDOW_ITEMS: " + e.getMessage());
        }
    }

    /**
     * Handle SET_SLOT - single slot update.
     */
    private void handleSetSlot(PacketEvent event) {
        if (event.isCancelled()) return;

        Player player = event.getPlayer();
        if (!hiddenArmor.contains(player.getUniqueId())) return;

        if (player.getGameMode() == GameMode.CREATIVE) return;

        PacketContainer packet = event.getPacket();

        try {
            // SET_SLOT (1.17+): integers[0]=windowId, integers[1]=stateId, integers[2]=slot
            var integers = packet.getIntegers();
            if (integers.size() < 3) return;

            int windowId = integers.read(0);
            if (windowId != 0) return;

            int slot = integers.read(2);

            if (!ARMOR_INVENTORY_SLOTS.contains(slot)) return;

            ItemStack item = packet.getItemModifier().read(0);

            if (slot == HELMET_SLOT) {
                ItemStack cosmeticHat = cosmeticHats.get(player.getUniqueId());
                if (cosmeticHat != null) {
                    ItemStack helmet = item != null && isArmor(item.getType()) ? item : null;
                    packet.getItemModifier().write(0, helmet != null ? createPlaceholder(cosmeticHat, helmet) : cosmeticHat.clone());
                } else if (item != null && item.getType() != Material.AIR && isArmor(item.getType())) {
                    packet.getItemModifier().write(0, createPlaceholder(getPlayerHead(player), item));
                }
                return;
            }

            if (item == null || item.getType() == Material.AIR || !isArmor(item.getType())) return;

            packet.getItemModifier().write(0, createGlassPlaceholder(item, slot));
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to handle SET_SLOT: " + e.getMessage());
        }
    }

    /**
     * Handle SET_CREATIVE_SLOT - stop creative from writing placeholders into the armor slots.
     */
    private void handleSetCreativeSlot(PacketEvent event) {
        Player player = event.getPlayer();
        if (!hiddenArmor.contains(player.getUniqueId())) return;

        PacketContainer packet = event.getPacket();
        try {
            int slot = packet.getIntegers().read(0);
            if (!ARMOR_INVENTORY_SLOTS.contains(slot)) return;

            ItemStack item = packet.getItemModifier().read(0);
            if (item == null) return;

            Material type = item.getType();
            // Intercept glass panes and player heads (our placeholders)
            if (type == Material.LIME_STAINED_GLASS_PANE || type == Material.PLAYER_HEAD) {
                // Cancel the packet, keep the real server-side armor
                event.setCancelled(true);
            }
        } catch (Exception e) {
            // ignore
        }
    }

    /**
     * Create a placeholder that shows the original armor's stats.
     */
    private ItemStack createGlassPlaceholder(ItemStack original, int slot) {
        return createPlaceholder(new ItemStack(Material.LIME_STAINED_GLASS_PANE), original);
    }

    /**
     * Create a placeholder with a custom display item, keeping the original armor's stats in the lore.
     */
    private ItemStack createPlaceholder(ItemStack displayItem, ItemStack original) {
        ItemStack result = displayItem.clone();
        ItemMeta meta = result.getItemMeta();
        if (meta == null) return result;

        meta.setDisplayName("§a§lHidden Armor");

        List<String> lore = new ArrayList<>();
        lore.add("§7Original: §f" + getItemDisplayName(original));

        if (original.hasItemMeta() && original.getItemMeta().hasEnchants()) {
            lore.add("");
            lore.add("§dEnchantments:");
            for (Map.Entry<Enchantment, Integer> entry : original.getEnchantments().entrySet()) {
                lore.add("  §7" + getEnchantmentName(entry.getKey()) + " " + toRoman(entry.getValue()));
            }
        }

        if (original.getType().getMaxDurability() > 0) {
            int maxDura = original.getType().getMaxDurability();
            int currentDura = maxDura - ((org.bukkit.inventory.meta.Damageable) original.getItemMeta()).getDamage();
            lore.add("");
            lore.add("§7Durability: §f" + currentDura + " / " + maxDura);
        }

        lore.add("");
        lore.add("§eUnequip to reveal the real item");

        meta.setLore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
        result.setItemMeta(meta);

        return result;
    }

    private String getItemDisplayName(ItemStack item) {
        if (item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
            return item.getItemMeta().getDisplayName();
        }
        return formatKey(item.getType().name());
    }

    /**
     * Turn an UPPER_SNAKE or lower_snake key into Title Case.
     */
    private String formatKey(String raw) {
        String name = raw.toLowerCase().replace('_', ' ');
        String[] words = name.split(" ");
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (!word.isEmpty()) {
                sb.append(Character.toUpperCase(word.charAt(0)))
                  .append(word.substring(1))
                  .append(' ');
            }
        }
        return sb.toString().trim();
    }

    private String getEnchantmentName(Enchantment ench) {
        return formatKey(ench.getKey().getKey());
    }

    private String toRoman(int num) {
        return switch (num) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            case 6 -> "VI";
            case 7 -> "VII";
            case 8 -> "VIII";
            case 9 -> "IX";
            case 10 -> "X";
            default -> String.valueOf(num);
        };
    }

    /**
     * Whether the material is armor (excluding elytra).
     */
    private boolean isArmor(Material material) {
        String name = material.name();
        return name.endsWith("_HELMET") || name.endsWith("_CHESTPLATE") ||
               name.endsWith("_LEGGINGS") || name.endsWith("_BOOTS") ||
               name.equals("TURTLE_HELMET");
    }

    /**
     * Player head, cached per player.
     */
    private ItemStack getPlayerHead(Player player) {
        return playerHeadCache.computeIfAbsent(player.getUniqueId(), uuid -> {
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            if (meta != null) {
                meta.setOwningPlayer(player);
                head.setItemMeta(meta);
            }
            return head;
        }).clone();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Lang.get(null, "player-only"));
            return true;
        }

        String cmdName = command.getName().toLowerCase();

        if (cmdName.equals("hidearmor")) {
            return handleHideArmor(player);
        } else if (cmdName.equals("cosmetichat")) {
            return handleCosmeticHat(player, args);
        }

        return false;
    }

    private boolean handleHideArmor(Player player) {
        UUID uuid = player.getUniqueId();
        boolean isHidden = hiddenArmor.contains(uuid);

        if (isHidden) {
            hiddenArmor.remove(uuid);
            storage.setArmorHidden(uuid, false);
            Lang.send(player, "armor.hide-off");
            Lang.send(player, "armor.hide-off-desc");
        } else {
            hiddenArmor.add(uuid);
            storage.setArmorHidden(uuid, true);
            Lang.send(player, "armor.hide-on");
            Lang.send(player, "armor.hide-on-desc");
        }

        refreshEquipmentForAll(player);
        player.updateInventory();
        return true;
    }

    private boolean handleCosmeticHat(Player player, String[] args) {
        UUID uuid = player.getUniqueId();

        if (args.length > 0 && args[0].equalsIgnoreCase("off")) {
            cosmeticHats.remove(uuid);
            storage.setCosmeticHat(uuid, null);
            Lang.send(player, "armor.hat-removed");
            refreshEquipmentForAll(player);
            return true;
        }

        ItemStack item = player.getInventory().getItemInMainHand();
        if (item == null || item.getType() == Material.AIR) {
            Lang.send(player, "armor.hat-need-item");
            Lang.send(player, "armor.hat-how-to-remove");
            return true;
        }

        ItemStack hatItem = item.clone();
        hatItem.setAmount(1);
        cosmeticHats.put(uuid, hatItem);
        storage.setCosmeticHat(uuid, serializeItem(hatItem));

        Lang.send(player, "armor.hat-set", "item", getItemDisplayName(hatItem));
        Lang.send(player, "armor.hat-how-to-remove");
        refreshEquipmentForAll(player);
        return true;
    }

    /**
     * Refresh the equipment that everyone (including the player) sees.
     */
    private void refreshEquipmentForAll(Player player) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (!viewer.canSee(player) && !viewer.equals(player)) continue;
            if (viewer.getWorld() != player.getWorld()) continue;
            if (viewer.getLocation().distanceSquared(player.getLocation()) > 16384) continue;
            sendEquipmentPacket(viewer, player);
        }
    }

    private void sendEquipmentPacket(Player viewer, Player target) {
        try {
            PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.ENTITY_EQUIPMENT);
            packet.getIntegers().write(0, target.getEntityId());

            List<Pair<EnumWrappers.ItemSlot, ItemStack>> equipmentList = new ArrayList<>();

            UUID targetUUID = target.getUniqueId();
            boolean hideArmor = hiddenArmor.contains(targetUUID);
            ItemStack cosmeticHat = cosmeticHats.get(targetUUID);

            // Main hand / off hand
            equipmentList.add(new Pair<>(EnumWrappers.ItemSlot.MAINHAND,
                target.getInventory().getItemInMainHand()));
            equipmentList.add(new Pair<>(EnumWrappers.ItemSlot.OFFHAND,
                target.getInventory().getItemInOffHand()));

            // Head: prefer cosmetic hat, otherwise the player head
            ItemStack headItem;
            if (cosmeticHat != null) {
                headItem = cosmeticHat.clone();
            } else if (hideArmor) {
                headItem = getPlayerHead(target);
            } else {
                headItem = target.getInventory().getHelmet();
            }
            equipmentList.add(new Pair<>(EnumWrappers.ItemSlot.HEAD, headItem));

            // Body armor (elytra excluded)
            ItemStack chestplate = target.getInventory().getChestplate();
            boolean isElytra = chestplate != null && chestplate.getType() == Material.ELYTRA;
            equipmentList.add(new Pair<>(EnumWrappers.ItemSlot.CHEST,
                (hideArmor && !isElytra) ? new ItemStack(Material.AIR) : chestplate));
            equipmentList.add(new Pair<>(EnumWrappers.ItemSlot.LEGS,
                hideArmor ? new ItemStack(Material.AIR) : target.getInventory().getLeggings()));
            equipmentList.add(new Pair<>(EnumWrappers.ItemSlot.FEET,
                hideArmor ? new ItemStack(Material.AIR) : target.getInventory().getBoots()));

            packet.getSlotStackPairLists().write(0, equipmentList);
            protocolManager.sendServerPacket(viewer, packet);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to send equipment packet: " + e.getMessage());
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        loadSettings(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        hiddenArmor.remove(uuid);
        cosmeticHats.remove(uuid);
        playerHeadCache.remove(uuid);
    }

    @EventHandler
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        if (!hiddenArmor.contains(uuid) && !cosmeticHats.containsKey(uuid)) return;

        // Delay 1 tick so the game mode change finishes before refreshing
        Sched.entityLater(plugin, player, () -> {
            if (player.isOnline()) {
                refreshEquipmentForAll(player);
                player.updateInventory();
            }
        }, 1L);
    }

    @EventHandler
    public void onPlayerDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        UUID uuid = player.getUniqueId();
        if (!hiddenArmor.contains(uuid) && !cosmeticHats.containsKey(uuid)) return;

        Sched.entityLater(plugin, player, () -> {
            if (player.isOnline()) {
                refreshEquipmentForAll(player);
            }
        }, 1L);
    }

    /**
     * Load a player's saved settings from local storage and refresh their display.
     */
    private void loadSettings(UUID uuid) {
        boolean armorHidden = storage.isArmorHidden(uuid);
        String hatData = storage.getCosmeticHat(uuid);

        if (armorHidden) {
            hiddenArmor.add(uuid);
        }
        if (hatData != null && !hatData.isEmpty()) {
            ItemStack hat = deserializeItem(hatData);
            if (hat != null) {
                cosmeticHats.put(uuid, hat);
            }
        }

        if (armorHidden || (hatData != null && !hatData.isEmpty())) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                Sched.entity(plugin, player, () -> {
                    refreshEquipmentForAll(player);
                    if (armorHidden) {
                        player.updateInventory();
                    }
                });
            }
        }
    }

    private String serializeItem(ItemStack item) {
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(outputStream);
            dataOutput.writeObject(item);
            dataOutput.close();
            return Base64.getEncoder().encodeToString(outputStream.toByteArray());
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to serialize item: " + e.getMessage());
            return null;
        }
    }

    private ItemStack deserializeItem(String data) {
        try {
            ByteArrayInputStream inputStream = new ByteArrayInputStream(Base64.getDecoder().decode(data));
            BukkitObjectInputStream dataInput = new BukkitObjectInputStream(inputStream);
            ItemStack item = (ItemStack) dataInput.readObject();
            dataInput.close();
            return item;
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to deserialize item: " + e.getMessage());
            return null;
        }
    }

    public void unregisterPacketListener() {
        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }
        if (protocolManager != null) {
            protocolManager.removePacketListeners(plugin);
        }
    }
}
