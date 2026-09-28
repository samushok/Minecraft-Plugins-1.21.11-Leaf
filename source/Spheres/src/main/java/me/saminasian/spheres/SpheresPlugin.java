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
    @Override public void onEnable() {
        for (String old : List.of("SummerBall", "Santaball")) {
            if (getServer().getPluginManager().getPlugin(old) != null) {
                getLogger().severe("Remove the separate " + old + " JAR before enabling Spheres.");
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
        }
        saveDefaultConfig();
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
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) { return List.of(); }
}
