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
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

public final class EclipseBallPlugin extends JavaPlugin implements Listener, CommandExecutor {
    private NamespacedKey marker;
    private NamespacedKey cooldownKey;
    private static final String PREFIX = "item.attributes.";
    private final Map<UUID, BukkitTask> activeAbilities = new HashMap<>();

    @Override public void onEnable() {
        saveDefaultConfig();
        marker = new NamespacedKey(this, "eclipse_ball");
        cooldownKey = new NamespacedKey(this, "eclipse_last_use");
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("eclipseball")).setExecutor(this);
    }

    @Override public void onDisable() {
        activeAbilities.values().forEach(BukkitTask::cancel);
        activeAbilities.clear();
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
            caster.sendMessage(color(getConfig().getString("messages.cooldown", "&cWait {seconds}s before using Eclipse again.")
                    .replace("{seconds}", Long.toString((until-now+999)/1000))));
            return;
        }
        if (activeAbilities.containsKey(caster.getUniqueId())) return;

        caster.getPersistentDataContainer().set(cooldownKey, PersistentDataType.LONG, now);
        startFog(caster);
    }

    private void startFog(Player caster) {
        final UUID casterId = caster.getUniqueId();
        final World world = caster.getWorld();
        final Location center = caster.getLocation().clone();
        final int durationTicks = (int) number("ability.duration-seconds", 6, 1, 60) * 20;
        final double maxRadius = number("ability.radius", 20, 1, 64);
        final double startRadius = Math.min(maxRadius, number("ability.fog.start-radius", 2, 0, 20));
        final double particleStep = number("ability.fog.particle-step", 1.5, 0.5, 4);
        final int particlesPerPoint = (int) number("ability.fog.particles-per-point", 2, 1, 6);
        final double fogHeight = number("ability.fog.height", 1.0, 0, 4);
        final int blindnessAmplifier = (int) number("ability.blindness.amplifier", 0, 0, 4);
        final boolean affectCaster = getConfig().getBoolean("ability.affect-caster", false);
        final String title = color(getConfig().getString("ability.title", "&5&lЭКЛИПС"));
        final String subtitle = color(getConfig().getString("ability.subtitle", "&8Туман поглощает свет"));

        for (Player viewer : Bukkit.getOnlinePlayers()) viewer.sendTitle(title, subtitle, 5, 35, 10);
        world.playSound(center, Sound.AMBIENT_CAVE, 0.8f, 0.65f);

        final int[] elapsed = {0};
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(this, () -> {
            Player currentCaster = Bukkit.getPlayer(casterId);
            if (currentCaster == null || !currentCaster.isOnline() || !currentCaster.isValid()
                    || !currentCaster.getWorld().equals(world) || elapsed[0] >= durationTicks) {
                BukkitTask active = activeAbilities.remove(casterId);
                if (active != null) active.cancel();
                return;
            }

            double progress = Math.min(1.0, elapsed[0] / (double) durationTicks);
            double radius = startRadius + (maxRadius - startRadius) * progress;
            double radiusSquared = radius * radius;
            double y = center.getY() + fogHeight;
            double circumference = 2.0 * Math.PI * radius;
            int points = Math.max(12, (int) Math.ceil(circumference / particleStep));
            for (int i = 0; i < points; i++) {
                double angle = (Math.PI * 2.0 * i) / points;
                Location particleLocation = new Location(world,
                        center.getX() + Math.cos(angle) * radius,
                        y,
                        center.getZ() + Math.sin(angle) * radius);
                world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, particleLocation,
                        particlesPerPoint, 0.25, 0.45, 0.25, 0.005);
            }
            // A light inner layer gives the impression of fog filling the area without spawning entities.
            if (getConfig().getBoolean("ability.fog.inner-layer", true)) {
                int innerPoints = Math.max(8, points / 2);
                double innerRadius = radius * 0.55;
                for (int i = 0; i < innerPoints; i++) {
                    double angle = (Math.PI * 2.0 * i) / innerPoints;
                    world.spawnParticle(Particle.SMOKE,
                            center.getX() + Math.cos(angle) * innerRadius, y - 0.35,
                            center.getZ() + Math.sin(angle) * innerRadius,
                            1, 0.3, 0.35, 0.3, 0.002);
                }
            }

            int remaining = Math.max(1, durationTicks - elapsed[0] + 2);
            for (Player target : world.getPlayers()) {
                if (!affectCaster && target.getUniqueId().equals(casterId)) continue;
                if (target.getLocation().distanceSquared(center) > radiusSquared) continue;
                if (getConfig().getBoolean("ability.blindness.enabled", true)) {
                    target.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, remaining,
                            blindnessAmplifier, false, false, true), true);
                }
            }
            elapsed[0] += 2;
        }, 0L, 2L);
        activeAbilities.put(casterId, task);
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
        if (target.getInventory().firstEmpty() == -1) {
            sender.sendMessage(ChatColor.RED + "Target inventory is full; Eclipse Ball was not given.");
            return true;
        }
        target.getInventory().addItem(createBall());
        sender.sendMessage(color(getConfig().getString("messages.given", "&aEclipse Ball given.")));
        return true;
    }
}
