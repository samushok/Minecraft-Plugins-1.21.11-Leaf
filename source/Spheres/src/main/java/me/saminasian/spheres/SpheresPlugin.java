package me.saminasian.spheres;

import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.List;

public final class SpheresPlugin extends JavaPlugin {
    private SummerShullerModule summer;
    private SantaSphere santa;
    private long guardianCacheUntilMillis = 0L;
    private double guardianCachedMultiplier = 1.0;
    @Override public void onEnable() {
        for (String old : List.of("SummerBall", "Santaball")) {
            if (getServer().getPluginManager().getPlugin(old) != null) {
                getLogger().severe("Remove the separate " + old + " JAR before enabling Spheres.");
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
        }
        saveDefaultConfig();
        // Keep the owner's existing values, but copy any NEW defaults introduced
        // by plugin updates (for example Santa gift rewards/random spawn settings).
        reloadConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();

        summer = new SummerShullerModule(this);
        santa = new SantaSphere(this);
        summer.start();
        santa.start();
        getLogger().info("Spheres enabled: SUMMER, SHULLER, SANTA — Leaf/Paper 1.21.11");
    }
    @Override public void onDisable() {
        Bukkit.getScheduler().cancelTasks(this);
        try { if (summer != null) summer.stop(); }
        finally { if (santa != null) santa.stop(); }
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used by a player.");
            return true;
        }
        String name = command.getName();

        // The server owner asked to edit item names/lore/textures/attributes directly
        // in config.yml without adding a fourth reload command. Re-read the single
        // shared config before every self-give command so the next issued sphere
        // always reflects the file currently on disk.
        reloadConfig();

        if (!player.hasPermission(name + ".give")) {
            String message = name.equals("santaball") ? getConfig().getString("santa.messages.no-permission", "&cУ вас нет прав.") : "&cУ вас нет прав.";
            player.sendMessage(org.bukkit.ChatColor.translateAlternateColorCodes('&', message));
            return true;
        }
        if (args.length != 0) {
            player.sendMessage("§eИспользование: /" + name + " (без аргументов)");
            return true;
        }
        ItemStack item = switch (name) {
            case "summerball" -> summer.createSummerBall();
            case "shullerball" -> summer.createShullerBall();
            case "santaball" -> santa.createSantaBall();
            default -> null;
        };
        if (item == null) return false;
        for (ItemStack remaining : player.getInventory().addItem(item).values())
            player.getWorld().dropItemNaturally(player.getLocation(), remaining);
        if (name.equals("santaball")) {
            String message = getConfig().getString("santa.messages.given", "&aSantaBall выдан игроку &f%player%&a.");
            player.sendMessage(org.bukkit.ChatColor.translateAlternateColorCodes('&', message.replace("%player%", player.getName())));
        } else player.sendMessage("§aШар выдан.");
        return true;
    }
    public double cosmeticMultiplier() {
        long now = System.currentTimeMillis();
        if (now < guardianCacheUntilMillis) return guardianCachedMultiplier;

        double multiplier = 1.0;
        org.bukkit.plugin.Plugin guardian = getServer().getPluginManager().getPlugin("ServerGuardian");
        if (guardian != null && guardian.isEnabled()) {
            try {
                Object value = guardian.getClass().getMethod("cosmeticMultiplier").invoke(guardian);
                if (value instanceof Number number) {
                    multiplier = Math.max(0.10, Math.min(1.0, number.doubleValue()));
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                multiplier = 1.0;
            }
        }

        guardianCachedMultiplier = multiplier;
        guardianCacheUntilMillis = now + 1000L;
        return multiplier;
    }

    public int scaleCosmeticCount(int base) {
        if (base <= 0) return 0;
        double multiplier = cosmeticMultiplier();
        if (multiplier >= 0.999) return base;
        return Math.max(1, (int)Math.round(base * multiplier));
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) { return List.of(); }
}
