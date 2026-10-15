package me.saminasian.signal67;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

public final class Signal67Plugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private static final int SECTOR_COUNT = 7;
    private static final double TWO_PI = Math.PI * 2.0;

    private final LegacyComponentSerializer legacy = LegacyComponentSerializer.legacyAmpersand();
    private final PlainTextComponentSerializer plain = PlainTextComponentSerializer.plainText();
    private final Set<UUID> activePlayers = new HashSet<>();
    // AsyncChatEvent may run concurrently; coalesce queued main-thread starts per player.
    private final Set<UUID> pendingChatStarts = ConcurrentHashMap.newKeySet();
    private final Set<UUID> finishingPlayers = new HashSet<>();
    private final Map<UUID, List<TextDisplay>> activeDisplays = new HashMap<>();

    private NamespacedKey cooldownKey;
    private NamespacedKey displayMarkerKey;
    private List<Reward> rewards = List.of();
    private volatile String triggerMessage = "67";
    private volatile boolean hideTriggerMessage = true;
    private volatile boolean triggerOnCancelledChat = false;

    private long cooldownSeconds = 3600L;
    private int maxConcurrentRituals = 4;
    private int ritualDurationTicks = 140;
    private int ritualUpdatePeriodTicks = 2;
    private double ritualRadius = 2.8;
    private double ritualDisplayHeight = 1.35;
    private float ritualViewRange = 0.55f;
    private String scanningTitleTemplate = "&b&lSIGNAL 67";
    private String scanningSubtitleTemplate = "&7Сканирование: %reward%";
    private String lockedTitleTemplate = "&6&lКАНАЛ 67 ЗАФИКСИРОВАН";
    private String lockedSubtitleTemplate = "%reward%";

    @Override
    public void onEnable() {
        saveDefaultConfig();
        cooldownKey = new NamespacedKey(this, "last_signal_67");
        displayMarkerKey = new NamespacedKey(this, "ritual_display");
        loadRuntimeConfig();
        loadRewards();
        cleanupStaleDisplays();

        Bukkit.getPluginManager().registerEvents(this, this);

        var command = Objects.requireNonNull(getCommand("signal67"), "signal67 command missing from plugin.yml");
        command.setExecutor(this);
        command.setTabCompleter(this);

        getLogger().info("Signal67 enabled. Active rewards: " + rewards.size());
    }

    @Override
    public void onDisable() {
        pendingChatStarts.clear();
        for (UUID uuid : new ArrayList<>(activeDisplays.keySet())) {
            cleanupRitual(uuid);
        }
        cleanupStaleDisplays();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cleanupRitual(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        UUID uuid = event.getEntity().getUniqueId();
        if (activePlayers.contains(uuid) && !finishingPlayers.contains(uuid)) {
            abortRitual(event.getEntity(), uuid);
        }
    }

    @EventHandler
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (activePlayers.contains(uuid) && !finishingPlayers.contains(uuid)) {
            abortRitual(event.getPlayer(), uuid);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onChat(AsyncChatEvent event) {
        // A cancelled chat message may have been blocked by a mute or chat moderation plugin.
        if (event.isCancelled() && !triggerOnCancelledChat) return;

        Player player = event.getPlayer();
        String message = plain.serialize(event.message()).trim();

        if (!message.equals(triggerMessage)) {
            return;
        }

        if (hideTriggerMessage) {
            event.setCancelled(true);
        }

        UUID uuid = player.getUniqueId();
        if (!pendingChatStarts.add(uuid)) return;

        try {
            Bukkit.getScheduler().runTask(this, () -> {
                try {
                    attemptStart(player, false);
                } finally {
                    pendingChatStarts.remove(uuid);
                }
            });
        } catch (RuntimeException exception) {
            pendingChatStarts.remove(uuid);
            if (isEnabled()) {
                getLogger().log(Level.WARNING, "Could not schedule Signal67 chat trigger", exception);
            }
        }
    }

    private void attemptStart(Player player, boolean force) {
        // Do not consume a cooldown or spawn displays for a player on the death screen.
        if (!player.isOnline() || player.isDead()) return;

        if (!force && !player.hasPermission("signal67.use")) {
            return;
        }

        UUID uuid = player.getUniqueId();

        if (activePlayers.contains(uuid)) {
            sendConfigured(player, "messages.already-active");
            return;
        }

        if (activePlayers.size() >= maxConcurrentRituals) {
            sendConfigured(player, "messages.server-busy");
            return;
        }

        if (rewards.isEmpty()) {
            sendConfigured(player, "messages.no-rewards");
            return;
        }

        if (!force && !player.hasPermission("signal67.bypasscooldown")) {
            long remaining = remainingCooldownMillis(player);
            if (remaining > 0L) {
                sendConfigured(player, "messages.cooldown", "%time%", formatDuration(remaining));
                return;
            }
        }

        List<Reward> rewardSnapshot = rewards;
        Reward finalReward = chooseWeightedReward(rewardSnapshot);
        int targetSector = ThreadLocalRandom.current().nextInt(SECTOR_COUNT);

        List<TextDisplay> displays;
        try {
            displays = spawnSectorDisplays(player);
        } catch (RuntimeException exception) {
            getLogger().log(Level.SEVERE, "Could not create Signal67 displays for " + player.getName(), exception);
            sendConfigured(player, "messages.aborted");
            return;
        }

        activePlayers.add(uuid);
        activeDisplays.put(uuid, displays);

        if (!force) {
            player.getPersistentDataContainer().set(cooldownKey, PersistentDataType.LONG, System.currentTimeMillis());
        }

        sendConfigured(player, "messages.started");
        runRitual(player, finalReward, targetSector, displays, rewardSnapshot);
    }

    private List<TextDisplay> spawnSectorDisplays(Player player) {
        List<TextDisplay> displays = new ArrayList<>(SECTOR_COUNT);
        Location center = player.getLocation().clone().add(0.0, ritualDisplayHeight, 0.0);
        double radius = ritualRadius;

        try {
            for (int i = 0; i < SECTOR_COUNT; i++) {
                int sector = i;
                double angle = TWO_PI * i / SECTOR_COUNT;
                Location spawn = center.clone().add(Math.cos(angle) * radius, 0.0, Math.sin(angle) * radius);

                TextDisplay display = player.getWorld().spawn(spawn, TextDisplay.class, entity -> {
                    entity.text(legacy.deserialize("&8[&b" + (sector + 1) + "&8]"));
                    entity.setBillboard(Display.Billboard.CENTER);
                    entity.setSeeThrough(true);
                    entity.setShadowed(true);
                    entity.setDefaultBackground(false);
                    entity.setViewRange(ritualViewRange);
                    entity.setPersistent(false);
                    entity.setInvulnerable(true);
                    entity.getPersistentDataContainer().set(displayMarkerKey, PersistentDataType.BYTE, (byte) 1);
                });
                displays.add(display);
            }
            return displays;
        } catch (RuntimeException exception) {
            for (TextDisplay display : displays) {
                if (display != null && display.isValid()) {
                    display.remove();
                }
            }
            throw exception;
        }
    }

    private void runRitual(
            Player player,
            Reward finalReward,
            int targetSector,
            List<TextDisplay> displays,
            List<Reward> rewardPool
    ) {
        int duration = ritualDurationTicks;
        int period = ritualUpdatePeriodTicks;
        double radius = ritualRadius;
        double height = ritualDisplayHeight;

        new BukkitRunnable() {
            private int elapsed;
            private int pointer;
            private int lastPointer = -1;
            private int lastDisplayedPointer = -1;

            @Override
            public void run() {
                UUID uuid = player.getUniqueId();

                if (activeDisplays.get(uuid) != displays) {
                    cancel();
                    return;
                }

                if (!player.isOnline() || player.isDead()) {
                    abortRitual(player, uuid);
                    cancel();
                    return;
                }

                if (displays.stream().anyMatch(display -> !display.isValid())) {
                    abortRitual(player, uuid);
                    cancel();
                    return;
                }

                World world = player.getWorld();
                if (displays.stream().anyMatch(display -> !display.getWorld().equals(world))) {
                    abortRitual(player, uuid);
                    cancel();
                    return;
                }

                elapsed += period;
                double progress = Math.min(1.0, elapsed / (double) duration);
                int step = Math.max(1, elapsed / period);
                int stride = progress < 0.55 ? 1 : progress < 0.78 ? 2 : progress < 0.92 ? 4 : 8;

                if (step % stride == 0) {
                    pointer = (pointer + 1) % SECTOR_COUNT;
                }
                if (progress >= 0.965) {
                    pointer = targetSector;
                }

                Location center = player.getLocation().clone().add(0.0, height, 0.0);
                double rotation = elapsed * 0.035;

                // Update only the two labels whose highlight state changed.
                // The seven displays still move every tick, but unchanged text is left alone.
                if (pointer != lastDisplayedPointer) {
                    if (lastDisplayedPointer >= 0) {
                        displays.get(lastDisplayedPointer).text(
                                legacy.deserialize("&8[&b" + (lastDisplayedPointer + 1) + "&8]")
                        );
                    }
                    displays.get(pointer).text(
                            legacy.deserialize("&6&l[ &e" + (pointer + 1) + " &6&l]")
                    );
                    lastDisplayedPointer = pointer;
                }

                for (int i = 0; i < SECTOR_COUNT; i++) {
                    double angle = rotation + TWO_PI * i / SECTOR_COUNT;
                    double bob = Math.sin((elapsed * 0.11) + i) * 0.18;
                    Location point = center.clone().add(Math.cos(angle) * radius, bob, Math.sin(angle) * radius);

                    TextDisplay display = displays.get(i);
                    display.teleport(point);

                    if (i == pointer) {
                        world.spawnParticle(Particle.ELECTRIC_SPARK, point, 2, 0.08, 0.08, 0.08, 0.01);
                    } else {
                        world.spawnParticle(Particle.END_ROD, point, 1, 0.02, 0.02, 0.02, 0.0);
                    }
                }

                world.spawnParticle(Particle.PORTAL, center, 4, 0.35, 0.25, 0.35, 0.05);
                world.spawnParticle(
                        Particle.DUST,
                        center,
                        2,
                        0.5,
                        0.15,
                        0.5,
                        0.0,
                        new Particle.DustOptions(Color.fromRGB(70, 210, 255), 1.0f)
                );

                if (pointer != lastPointer) {
                    lastPointer = pointer;
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.35f, 0.9f + pointer * 0.06f);
                }

                if (elapsed % 12 < period) {
                    Reward preview = rewardPool.get(ThreadLocalRandom.current().nextInt(rewardPool.size()));
                    showScanningTitle(player, preview);
                }

                if (elapsed >= duration) {
                    finishRitual(player, finalReward, targetSector, displays);
                    cancel();
                }
            }
        }.runTaskTimer(this, 0L, period);
    }

    private void showScanningTitle(Player player, Reward preview) {
        String title = color(scanningTitleTemplate);
        String subtitle = color(scanningSubtitleTemplate.replace("%reward%", preview.display()));
        player.sendTitle(title, subtitle, 0, 14, 0);
    }

    private void finishRitual(Player player, Reward reward, int targetSector, List<TextDisplay> displays) {
        UUID uuid = player.getUniqueId();

        for (int i = 0; i < displays.size(); i++) {
            TextDisplay display = displays.get(i);
            if (!display.isValid()) continue;
            display.text(legacy.deserialize(i == targetSector ? "&6&l✦ &e&l67 &6&l✦" : "&8×"));
        }

        Location center = player.getLocation().clone().add(0.0, 1.2, 0.0);
        World world = player.getWorld();

        world.spawnParticle(Particle.END_ROD, center, 35, 1.25, 0.8, 1.25, 0.05);
        world.spawnParticle(Particle.ELECTRIC_SPARK, center, 28, 1.0, 0.7, 1.0, 0.08);
        world.playSound(center, Sound.BLOCK_BEACON_ACTIVATE, 0.9f, 1.35f);
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.9f, 1.1f);

        String title = color(lockedTitleTemplate);
        String subtitle = color(lockedSubtitleTemplate.replace("%reward%", reward.display()));
        player.sendTitle(title, subtitle, 5, 45, 15);

        finishingPlayers.add(uuid);

        executeReward(player, reward);
        sendConfigured(player, "messages.won", "%reward%", reward.display());

        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (activeDisplays.get(uuid) == displays) cleanupRitual(uuid);
        }, 35L);
    }

    private void abortRitual(Player player, UUID uuid) {
        if (player.isOnline()) sendConfigured(player, "messages.aborted");
        cleanupRitual(uuid);
    }

    private void executeReward(Player player, Reward reward) {
        for (String rawCommand : reward.commands()) {
            String command = rawCommand
                    .replace("%player%", player.getName())
                    .replace("%uuid%", player.getUniqueId().toString())
                    .trim();

            if (command.startsWith("/")) {
                command = command.substring(1).trim();
            }
            if (command.isEmpty()) {
                getLogger().warning("Skipping blank command for reward '" + reward.id() + "'.");
                continue;
            }

            try {
                boolean handled = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
                if (!handled) {
                    getLogger().warning("Reward command returned false: " + command);
                }
            } catch (RuntimeException exception) {
                getLogger().log(
                        Level.SEVERE,
                        "Reward command failed for reward '" + reward.id() + "': " + command,
                        exception
                );
            }
        }
    }

    private Reward chooseWeightedReward(List<Reward> rewardPool) {
        double totalWeight = rewardPool.stream().mapToDouble(Reward::weight).sum();
        double roll = ThreadLocalRandom.current().nextDouble(totalWeight);

        for (Reward reward : rewardPool) {
            roll -= reward.weight();
            if (roll < 0.0) return reward;
        }

        return rewardPool.get(rewardPool.size() - 1);
    }

    private void loadRuntimeConfig() {
        String configuredTrigger = getConfig().getString("trigger", "67");
        triggerMessage = configuredTrigger == null || configuredTrigger.isBlank() ? "67" : configuredTrigger.trim();
        hideTriggerMessage = getConfig().getBoolean("hide-trigger-message", true);
        triggerOnCancelledChat = getConfig().getBoolean("trigger-on-cancelled-chat", false);

        cooldownSeconds = clampLong(getConfig().getLong("cooldown-seconds", 3600L), 0L, 2_592_000L);
        maxConcurrentRituals = clampInt(getConfig().getInt("max-concurrent-rituals", 4), 1, 16);
        ritualDurationTicks = clampInt(getConfig().getInt("ritual.duration-ticks", 140), 40, 600);
        ritualUpdatePeriodTicks = clampInt(getConfig().getInt("ritual.update-period-ticks", 2), 1, 10);
        ritualRadius = clampDouble(getConfig().getDouble("ritual.radius", 2.8), 1.2, 8.0, 2.8);
        ritualDisplayHeight = clampDouble(getConfig().getDouble("ritual.display-height", 1.35), 0.0, 5.0, 1.35);
        ritualViewRange = (float) clampDouble(getConfig().getDouble("ritual.view-range", 0.55), 0.1, 2.0, 0.55);

        scanningTitleTemplate = getConfig().getString("titles.scanning-title", "&b&lSIGNAL 67");
        scanningSubtitleTemplate = getConfig().getString("titles.scanning-subtitle", "&7Сканирование: %reward%");
        lockedTitleTemplate = getConfig().getString("titles.locked-title", "&6&lКАНАЛ 67 ЗАФИКСИРОВАН");
        lockedSubtitleTemplate = getConfig().getString("titles.locked-subtitle", "%reward%");

        getLogger().info(
                "Runtime config: cooldown=" + cooldownSeconds + "s, maxConcurrent=" + maxConcurrentRituals
                        + ", duration=" + ritualDurationTicks + "t, period=" + ritualUpdatePeriodTicks
                        + "t, radius=" + ritualRadius
        );
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static long clampLong(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clampDouble(double value, double min, double max, double fallback) {
        if (!Double.isFinite(value)) return fallback;
        return Math.max(min, Math.min(max, value));
    }

    private void loadRewards() {
        ConfigurationSection rewardsSection = getConfig().getConfigurationSection("rewards");
        if (rewardsSection == null) {
            rewards = List.of();
            getLogger().warning("No rewards section found in config.yml");
            return;
        }

        List<Reward> loaded = new ArrayList<>();
        for (String key : rewardsSection.getKeys(false)) {
            ConfigurationSection section = rewardsSection.getConfigurationSection(key);
            if (section == null || !section.getBoolean("enabled", true)) continue;

            double weight = section.getDouble("weight", 0.0);
            String display = section.getString("display", key);
            List<String> commands = section.getStringList("commands");

            if (!Double.isFinite(weight) || weight <= 0.0 || weight > 1_000_000_000.0) {
                getLogger().warning("Skipping reward '" + key + "': weight must be finite and between 0 and 1,000,000,000.");
                continue;
            }

            List<String> usableCommands = commands.stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(command -> !command.isEmpty())
                    .toList();

            if (usableCommands.isEmpty()) {
                getLogger().warning("Skipping reward '" + key + "': commands list is empty.");
                continue;
            }

            if (display == null || display.isBlank()) {
                display = key;
            }

            loaded.add(new Reward(key, display, weight, List.copyOf(usableCommands)));
        }
        rewards = List.copyOf(loaded);
    }

    private long remainingCooldownMillis(Player player) {
        Long lastUse = player.getPersistentDataContainer().get(cooldownKey, PersistentDataType.LONG);
        if (lastUse == null) return 0L;

        long cooldownMillis = cooldownSeconds * 1000L;
        return Math.max(0L, lastUse + cooldownMillis - System.currentTimeMillis());
    }

    private String formatDuration(long millis) {
        long totalSeconds = Math.max(0L, (millis + 999L) / 1000L);
        long hours = totalSeconds / 3600L;
        long minutes = totalSeconds % 3600L / 60L;
        long seconds = totalSeconds % 60L;

        if (hours > 0L) return hours + "ч " + minutes + "м";
        if (minutes > 0L) return minutes + "м " + seconds + "с";
        return seconds + "с";
    }

    private void cleanupRitual(UUID uuid) {
        List<TextDisplay> displays = activeDisplays.remove(uuid);
        if (displays != null) {
            for (TextDisplay display : displays) {
                if (display != null && display.isValid()) display.remove();
            }
        }
        activePlayers.remove(uuid);
        finishingPlayers.remove(uuid);
    }

    private void cleanupStaleDisplays() {
        if (displayMarkerKey == null) return;
        for (World world : Bukkit.getWorlds()) {
            for (TextDisplay display : world.getEntitiesByClass(TextDisplay.class)) {
                if (display.getPersistentDataContainer().has(displayMarkerKey, PersistentDataType.BYTE)) {
                    display.remove();
                }
            }
        }
    }

    private void sendConfigured(CommandSender sender, String path, String... replacements) {
        String value = getConfig().getString(path, "");
        if (value == null || value.isBlank()) return;

        for (int i = 0; i + 1 < replacements.length; i += 2) {
            value = value.replace(replacements[i], replacements[i + 1]);
        }

        sender.sendMessage(legacy.deserialize(getConfig().getString("messages.prefix", "") + value));
    }

    private String color(String value) {
        return ChatColor.translateAlternateColorCodes('&', value == null ? "" : value);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String action = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);

        switch (action) {
            case "status" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Use /signal67 reload, reset <player>, or test <player> from console.");
                    return true;
                }
                long remaining = remainingCooldownMillis(player);
                if (remaining <= 0L || player.hasPermission("signal67.bypasscooldown")) {
                    sendConfigured(player, "messages.status-ready");
                } else {
                    sendConfigured(player, "messages.status-wait", "%time%", formatDuration(remaining));
                }
                return true;
            }
            case "chances" -> {
                if (rewards.isEmpty()) {
                    sendConfigured(sender, "messages.no-rewards");
                    return true;
                }

                double totalWeight = rewards.stream().mapToDouble(Reward::weight).sum();
                sender.sendMessage(legacy.deserialize("&8[&b67&8] &fШансы наград:"));
                for (Reward reward : rewards) {
                    double chance = reward.weight() / totalWeight * 100.0;
                    String percent = String.format(Locale.US, "%.2f%%", chance);
                    sender.sendMessage(legacy.deserialize("&8• " + reward.display() + " &7— &f" + percent));
                }
                return true;
            }
            case "reload" -> {
                if (!sender.hasPermission("signal67.admin")) {
                    sender.sendMessage(Component.text("No permission."));
                    return true;
                }
                reloadConfig();
                loadRuntimeConfig();
                loadRewards();
                sendConfigured(sender, "messages.reloaded", "%count%", Integer.toString(rewards.size()));
                return true;
            }
            case "reset" -> {
                if (!sender.hasPermission("signal67.admin")) {
                    sender.sendMessage(Component.text("No permission."));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage("/signal67 reset <player>");
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    sender.sendMessage("Player must be online.");
                    return true;
                }
                target.getPersistentDataContainer().remove(cooldownKey);
                sendConfigured(sender, "messages.reset", "%player%", target.getName());
                return true;
            }
            case "test" -> {
                if (!sender.hasPermission("signal67.admin")) {
                    sender.sendMessage(Component.text("No permission."));
                    return true;
                }
                Player target;
                if (args.length >= 2) {
                    target = Bukkit.getPlayerExact(args[1]);
                } else if (sender instanceof Player player) {
                    target = player;
                } else {
                    sender.sendMessage("/signal67 test <player>");
                    return true;
                }
                if (target == null) {
                    sender.sendMessage("Player must be online.");
                    return true;
                }
                attemptStart(target, true);
                return true;
            }
            default -> {
                sender.sendMessage("/signal67 [status|chances|reload|reset <player>|test [player]]");
                return true;
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> values = new ArrayList<>(List.of("status", "chances"));
            if (sender.hasPermission("signal67.admin")) values.addAll(List.of("reload", "reset", "test"));
            return partial(args[0], values);
        }

        if (args.length == 2 && sender.hasPermission("signal67.admin")
                && (args[0].equalsIgnoreCase("reset") || args[0].equalsIgnoreCase("test"))) {
            return partial(args[1], Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
        }

        return Collections.emptyList();
    }

    private List<String> partial(String input, List<String> values) {
        String lower = input.toLowerCase(Locale.ROOT);
        return values.stream()
                .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lower))
                .sorted()
                .toList();
    }

    private record Reward(String id, String display, double weight, List<String> commands) {}
}
