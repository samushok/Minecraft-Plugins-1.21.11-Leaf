package me.saminasian.eclipse;

import org.bukkit.*;
import org.bukkit.attribute.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.*;
import java.util.*;

public final class EclipseBallPlugin extends JavaPlugin implements Listener, CommandExecutor {
    private NamespacedKey marker;
    private NamespacedKey cooldownKey;
    private static final String PREFIX = "item.attributes.";
    @Override public void onEnable() {
        saveDefaultConfig();
        marker = new NamespacedKey(this, "eclipse_ball");
        cooldownKey = new NamespacedKey(this, "eclipse_last_use");
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("eclipseball")).setExecutor(this);
    }
    private String color(String value) { return ChatColor.translateAlternateColorCodes('&', value == null ? "" : value); }
    private double number(String path, double fallback, double min, double max) {
        double value = getConfig().getDouble(path, fallback);
        return Double.isFinite(value) ? Math.max(min, Math.min(max, value)) : fallback;
    }
    private ItemStack createBall() {
        FileConfiguration c = getConfig();
        Material material = Material.matchMaterial(c.getString("item.material", "NETHER_STAR"));
        if (material == null || !material.isItem() || material.isAir()) material = Material.NETHER_STAR;
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(color(c.getString("item.name", "&5Eclipse")));
        meta.setLore(c.getStringList("item.lore").stream().map(this::color).toList());
        int model = c.getInt("item.custom-model-data", 6701);
        if (model >= 0) meta.setCustomModelData(model);
        meta.getPersistentDataContainer().set(marker, PersistentDataType.BYTE, (byte) 1);
        add(meta, Attribute.ATTACK_DAMAGE, "damage", number(PREFIX+"attack-damage", 4, -100, 100), AttributeModifier.Operation.ADD_NUMBER);
        add(meta, Attribute.ATTACK_SPEED, "attack_speed", number(PREFIX+"attack-speed-percent", 30, -100, 500) / 100, AttributeModifier.Operation.MULTIPLY_SCALAR_1);
        add(meta, Attribute.MOVEMENT_SPEED, "movement", number(PREFIX+"movement-speed-percent", 25, -100, 500) / 100, AttributeModifier.Operation.MULTIPLY_SCALAR_1);
        add(meta, Attribute.MAX_HEALTH, "health", number(PREFIX+"max-health", 8, -100, 100), AttributeModifier.Operation.ADD_NUMBER);
        add(meta, Attribute.ARMOR, "armor", number(PREFIX+"armor", 2, -100, 100), AttributeModifier.Operation.ADD_NUMBER);
        item.setItemMeta(meta);
        return item;
    }
    private void add(ItemMeta meta, Attribute attribute, String name, double value, AttributeModifier.Operation operation) {
        meta.addAttributeModifier(attribute, new AttributeModifier(new NamespacedKey(this, name), value, operation, EquipmentSlotGroup.MAINHAND));
    }
    private boolean isBall(ItemStack item) {
        return item != null && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(marker, PersistentDataType.BYTE);
    }
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onSneak(PlayerToggleSneakEvent event) {
        if (!event.isSneaking()) return;
        Player caster = event.getPlayer();
        if (!isBall(caster.getInventory().getItemInMainHand())) return;
        long now = System.currentTimeMillis();
        Long lastUse = caster.getPersistentDataContainer().get(cooldownKey, PersistentDataType.LONG);
        long delay = (long) number("ability.cooldown-seconds", 60, 0, 86400);
        long until = lastUse == null ? 0L : lastUse + delay * 1000L;
        if (now < until) {
            caster.sendMessage(color(getConfig().getString("messages.cooldown", "&cWait {seconds}s").replace("{seconds}", Long.toString((until-now+999)/1000))));
            return;
        }
        caster.getPersistentDataContainer().set(cooldownKey, PersistentDataType.LONG, now);
        double radius = number("ability.radius", 8, 0, 64);
        double squared = radius * radius;
        String title = color(getConfig().getString("ability.title", "&5&lЭКЛИПС"));
        String subtitle = color(getConfig().getString("ability.subtitle", "&8Тьма поглощает свет"));
        // The announcement is global; only nearby players in the caster's world receive debuffs.
        for (Player viewer : Bukkit.getOnlinePlayers()) viewer.sendTitle(title, subtitle, 5, 35, 10);
        Location origin = caster.getLocation();
        for (Player target : caster.getWorld().getPlayers()) {
            if (target.getUniqueId().equals(caster.getUniqueId()) || target.getLocation().distanceSquared(origin) > squared) continue;
            effect(target, "ability.blindness", PotionEffectType.BLINDNESS);
            effect(target, "ability.wither", PotionEffectType.WITHER);
        }
        int particles = (int) number("ability.particles", 20, 0, 100);
        if (particles > 0) caster.getWorld().spawnParticle(Particle.SMOKE, caster.getLocation().add(0, 1, 0), particles, 1.5, 0.8, 1.5, 0.01);
    }
    private void effect(Player player, String path, PotionEffectType type) {
        if (!getConfig().getBoolean(path + ".enabled", true)) return;
        int duration = (int) number(path + ".duration-seconds", 5, 0, 60) * 20;
        if (duration == 0) return;
        int amplifier = (int) number(path + ".amplifier", 0, 0, 4);
        player.addPotionEffect(new PotionEffect(type, duration, amplifier, false, true, true));
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("eclipseball.admin")) { sender.sendMessage(ChatColor.RED + "No permission."); return true; }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig(); sender.sendMessage(ChatColor.GREEN + "EclipseBall config reloaded."); return true;
        }
        Player target;
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) target = Bukkit.getPlayerExact(args[1]);
        else if (args.length == 0 && sender instanceof Player player) target = player;
        else { sender.sendMessage("/eclipseball [give <player>|reload]"); return true; }
        if (target == null) { sender.sendMessage(ChatColor.RED + "Player not online."); return true; }
        Map<Integer, ItemStack> leftovers = target.getInventory().addItem(createBall());
        leftovers.values().forEach(stack -> target.getWorld().dropItemNaturally(target.getLocation(), stack));
        sender.sendMessage(color(getConfig().getString("messages.given", "&aEclipse Ball given.")));
        return true;
    }
}
