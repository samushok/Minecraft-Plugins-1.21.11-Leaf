package me.saminasian.mogritual;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

public final class MogRitualPlugin extends JavaPlugin implements Listener {
    private final LinkedHashMap<UUID, Long> pending = new LinkedHashMap<>();
    private volatile String triggerNormalized = "я тебя могну";
    private volatile boolean hideTriggerMessage = false;
    private boolean ritualActive = false;
    private long lastRitualMillis = 0L;
    private Path cooldownFile;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadRuntimeSettings();
        cooldownFile = getDataFolder().toPath().resolve("cooldown.properties");
        loadCooldown();
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("MogRitual enabled for Leaf / Paper 1.21.11");
    }

    @Override
    public void onDisable() {
        saveCooldown();
        pending.clear();
        ritualActive = false;
    }

    @EventHandler(ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        String message = normalize(event.getMessage());
        if (triggerNormalized.isBlank() || !message.equals(triggerNormalized)) {
            return;
        }

        if (hideTriggerMessage) {
            event.setCancelled(true);
        }

        UUID playerId = event.getPlayer().getUniqueId();
        Bukkit.getScheduler().runTask(this, () -> handleTrigger(playerId));
    }

    private void handleTrigger(UUID playerId) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            return;
        }

        cleanupPending();

        if (ritualActive) {
            player.sendMessage(message("messages.busy", "&d[MOG] &eРитуал уже идёт."));
            return;
        }

        long remaining = remainingCooldownSeconds();
        if (remaining > 0L) {
            player.sendMessage(message("messages.cooldown", "&d[MOG] &eРитуал снова будет доступен через &f%time%.")
                    .replace("%time%", formatDuration(remaining)));
            return;
        }

        if (pending.containsKey(playerId)) {
            player.sendMessage(message("messages.already-joined", "&d[MOG] &7Ты уже участвуешь в текущем сборе."));
            return;
        }

        double gatherRadius = d("trigger.gather-radius", 12.0, 1.0, 64.0);
        Player anchor = firstPendingPlayer();
        if (anchor != null) {
            if (anchor.getWorld() != player.getWorld()
                    || anchor.getLocation().distanceSquared(player.getLocation()) > gatherRadius * gatherRadius) {
                player.sendMessage(message("messages.too-far", "&d[MOG] &cНужно быть в радиусе %radius% блоков от остальных участников.")
                        .replace("%radius%", cleanNumber(gatherRadius)));
                return;
            }
        } else if (!pending.isEmpty()) {
            pending.clear();
        }

        pending.put(playerId, System.currentTimeMillis());

        int required = i("trigger.required-players", 3, 2, 8);
        int count = pending.size();
        String joined = message("messages.joined", "&d[MOG] &f%player% &7вошёл в ритуал. &f%count%/%required%")
                .replace("%player%", player.getName())
                .replace("%count%", String.valueOf(count))
                .replace("%required%", String.valueOf(required));
        for (UUID id : pending.keySet()) {
            Player participant = Bukkit.getPlayer(id);
            if (participant != null && participant.isOnline()) {
                participant.sendMessage(joined);
            }
        }

        if (count < required) {
            return;
        }

        List<Player> participants = new ArrayList<>();
        for (UUID id : pending.keySet()) {
            Player participant = Bukkit.getPlayer(id);
            if (participant != null && participant.isOnline()) {
                participants.add(participant);
            }
            if (participants.size() >= required) {
                break;
            }
        }
        pending.clear();

        if (participants.size() < required || !participantsWithinRadius(participants, gatherRadius)) {
            for (Player participant : participants) {
                participant.sendMessage(message("messages.ritual-abort",
                        "&d[MOG] &cРитуал отменён: один из участников вышел или ушёл слишком далеко."));
            }
            return;
        }

        startRitual(participants);
    }

    private void cleanupPending() {
        long cutoff = System.currentTimeMillis() - i("trigger.window-seconds", 20, 5, 120) * 1000L;
        pending.entrySet().removeIf(entry -> {
            Player player = Bukkit.getPlayer(entry.getKey());
            return entry.getValue() < cutoff || player == null || !player.isOnline();
        });
    }

    private Player firstPendingPlayer() {
        for (UUID id : pending.keySet()) {
            Player player = Bukkit.getPlayer(id);
            if (player != null && player.isOnline()) {
                return player;
            }
        }
        return null;
    }

    private boolean participantsWithinRadius(List<Player> players, double radius) {
        if (players.isEmpty()) {
            return false;
        }
        Player anchor = players.get(0);
        double radiusSquared = radius * radius;
        for (Player player : players) {
            if (player.getWorld() != anchor.getWorld()
                    || player.getLocation().distanceSquared(anchor.getLocation()) > radiusSquared) {
                return false;
            }
        }
        return true;
    }

    private void startRitual(List<Player> participants) {
        List<Reward> rewards = loadRewards();
        if (rewards.isEmpty()) {
            for (Player participant : participants) {
                participant.sendMessage(message("messages.no-rewards", "&d[MOG] &cВ config.yml нет доступных наград."));
            }
            return;
        }

        World world = participants.get(0).getWorld();
        Location center = averageLocation(participants);
        ritualActive = true;
        lastRitualMillis = System.currentTimeMillis();
        saveCooldown();

        String startMessage = message("messages.ritual-start",
                "&d&l[MOG] &fТри игрока завершили фразу. Ритуал начинается...");
        for (Player participant : participants) {
            participant.sendMessage(startMessage);
            participant.sendTitle(color("&d&lЯ ТЕБЯ МОГНУ"), color("&fРитуал начинается..."), 5, 25, 5);
        }

        playCustomSound(participants, center);

        final int duration = i("ritual.duration-ticks", 80, 20, 200);
        final int interval = i("ritual.update-interval-ticks", 5, 2, 20);
        final double maxDrift = d("trigger.gather-radius", 12.0, 1.0, 64.0) * 2.0;

        new BukkitRunnable() {
            private int elapsed = 0;

            @Override
            public void run() {
                if (!participantsStillValid(participants, world, center, maxDrift)) {
                    abortRitual(participants);
                    cancel();
                    return;
                }

                if (elapsed >= duration) {
                    cancel();
                    startRoulette(participants, center, rewards);
                    return;
                }

                renderRitual(world, center, participants, elapsed);

                if (getConfig().getBoolean("ritual.sounds.vanilla-enabled", true) && elapsed % 20 == 0) {
                    float volume = (float)d("ritual.sounds.vanilla-volume", 0.8, 0.0, 4.0);
                    float pitch = 0.7f + Math.min(0.8f, elapsed / (float)Math.max(1, duration));
                    world.playSound(center, Sound.BLOCK_NOTE_BLOCK_BASS, SoundCategory.PLAYERS, volume, pitch);
                }

                elapsed += interval;
            }
        }.runTaskTimer(this, 0L, interval);
    }

    private void renderRitual(World world, Location center, List<Player> participants, int elapsed) {
        if (!getConfig().getBoolean("ritual.particles.enabled", true)) {
            return;
        }

        int ringPoints = i("ritual.particles.ring-points", 12, 4, 40);
        double ringRadius = d("ritual.particles.ring-radius", 2.6, 0.5, 8.0);
        double rotation = elapsed * 0.08;

        for (int point = 0; point < ringPoints; point++) {
            double angle = rotation + Math.PI * 2.0 * point / ringPoints;
            Location particle = center.clone().add(
                    Math.cos(angle) * ringRadius,
                    0.18 + (point % 2) * 0.15,
                    Math.sin(angle) * ringRadius
            );
            world.spawnParticle(Particle.END_ROD, particle, 1, 0.0, 0.0, 0.0, 0.0);
        }

        int enchant = i("ritual.particles.center-enchant-count", 12, 0, 50);
        if (enchant > 0) {
            world.spawnParticle(Particle.ENCHANT, center.clone().add(0.0, 1.0, 0.0),
                    enchant, 1.6, 1.0, 1.6, 0.03);
        }

        int witch = i("ritual.particles.player-witch-count", 3, 0, 20);
        if (witch > 0) {
            for (Player participant : participants) {
                world.spawnParticle(Particle.WITCH, participant.getLocation().clone().add(0.0, 1.0, 0.0),
                        witch, 0.35, 0.65, 0.35, 0.01);
            }
        }
    }

    private void startRoulette(List<Player> participants, Location center, List<Reward> rewards) {
        String mode = getConfig().getString("roulette.reward-mode", "EACH");
        boolean oneRandom = mode != null && mode.equalsIgnoreCase("ONE_RANDOM");
        Map<UUID, Reward> finals = new LinkedHashMap<>();

        if (oneRandom) {
            Player winner = participants.get(ThreadLocalRandom.current().nextInt(participants.size()));
            finals.put(winner.getUniqueId(), pickReward(rewards));
        } else {
            for (Player participant : participants) {
                finals.put(participant.getUniqueId(), pickReward(rewards));
            }
        }

        final int duration = i("roulette.duration-ticks", 40, 12, 120);
        final int interval = i("roulette.update-interval-ticks", 4, 2, 20);
        final String title = color(getConfig().getString("roulette.title", "&d&lЯ ТЕБЯ МОГНУ"));

        new BukkitRunnable() {
            private int elapsed = 0;

            @Override
            public void run() {
                if (elapsed >= duration) {
                    finishRoulette(participants, finals);
                    ritualActive = false;
                    cancel();
                    return;
                }

                for (Player participant : participants) {
                    if (!participant.isOnline()) {
                        continue;
                    }
                    Reward preview = pickReward(rewards);
                    participant.sendTitle(title, color("&7▶ &f" + preview.displayName() + " &7◀"), 0, interval + 2, 0);
                    participant.playSound(participant.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING,
                            SoundCategory.PLAYERS, 0.35f, 1.0f + elapsed / (float)Math.max(1, duration));
                }

                elapsed += interval;
            }
        }.runTaskTimer(this, 0L, interval);
    }

    private void finishRoulette(List<Player> participants, Map<UUID, Reward> finals) {
        String finalSubtitle = getConfig().getString("roulette.final-subtitle", "&aВыпало: &f%reward%");
        for (Player participant : participants) {
            if (!participant.isOnline()) {
                continue;
            }

            Reward reward = finals.get(participant.getUniqueId());
            if (reward == null) {
                participant.sendMessage(message("messages.spectator",
                        "&d[MOG] &7В режиме ONE_RANDOM награду получил другой участник."));
                participant.sendTitle(color("&d&lЯ ТЕБЯ МОГНУ"), color("&7Сегодня без награды"), 5, 35, 10);
                continue;
            }

            executeReward(participant, reward);
            participant.sendMessage(message("messages.reward", "&d[MOG] &fТебе выпало: &a%reward%")
                    .replace("%reward%", reward.displayName()));
            participant.sendTitle(color("&d&lЯ ТЕБЯ МОГНУ"),
                    color(finalSubtitle.replace("%reward%", reward.displayName())), 5, 50, 15);
            participant.playSound(participant.getLocation(), Sound.ENTITY_PLAYER_LEVELUP,
                    SoundCategory.PLAYERS, 1.0f, 1.1f);
        }
    }

    private void abortRitual(List<Player> participants) {
        ritualActive = false;
        for (Player participant : participants) {
            if (participant.isOnline()) {
                participant.sendMessage(message("messages.ritual-abort",
                        "&d[MOG] &cРитуал отменён: один из участников вышел или ушёл слишком далеко."));
            }
        }
    }

    private boolean participantsStillValid(List<Player> participants, World world, Location center, double maxDrift) {
        double maxDistanceSquared = maxDrift * maxDrift;
        for (Player participant : participants) {
            if (!participant.isOnline()
                    || participant.isDead()
                    || participant.getWorld() != world
                    || participant.getLocation().distanceSquared(center) > maxDistanceSquared) {
                return false;
            }
        }
        return true;
    }

    private Location averageLocation(List<Player> participants) {
        World world = participants.get(0).getWorld();
        double x = 0.0, y = 0.0, z = 0.0;
        for (Player participant : participants) {
            Location location = participant.getLocation();
            x += location.getX();
            y += location.getY();
            z += location.getZ();
        }
        double count = participants.size();
        return new Location(world, x / count, y / count, z / count);
    }

    private void playCustomSound(List<Player> participants, Location center) {
        String key = getConfig().getString("ritual.sounds.custom-key", "");
        if (key == null || key.isBlank()) {
            return;
        }
        float volume = (float)d("ritual.sounds.custom-volume", 1.0, 0.0, 10.0);
        float pitch = (float)d("ritual.sounds.custom-pitch", 1.0, 0.01, 2.0);
        for (Player participant : participants) {
            participant.playSound(center, key.trim(), SoundCategory.PLAYERS, volume, pitch);
        }
    }

    private List<Reward> loadRewards() {
        ConfigurationSection section = getConfig().getConfigurationSection("rewards");
        if (section == null) {
            return List.of();
        }

        List<Reward> rewards = new ArrayList<>();
        for (String id : section.getKeys(false)) {
            ConfigurationSection reward = section.getConfigurationSection(id);
            if (reward == null) {
                continue;
            }
            double weight = reward.getDouble("weight", 0.0);
            if (!Double.isFinite(weight) || weight <= 0.0) {
                continue;
            }
            double chance = reward.getDouble("chance", weight);
            String display = reward.getString("display-name", id);
            List<String> commands = reward.getStringList("commands");
            rewards.add(new Reward(id, display == null ? id : display, weight, chance, List.copyOf(commands)));
        }
        return rewards;
    }

    private Reward pickReward(List<Reward> rewards) {
        double total = 0.0;
        for (Reward reward : rewards) {
            total += reward.weight();
        }
        if (!(total > 0.0) || !Double.isFinite(total)) {
            return rewards.get(0);
        }

        double roll = ThreadLocalRandom.current().nextDouble(total);
        double cursor = 0.0;
        for (Reward reward : rewards) {
            cursor += reward.weight();
            if (roll < cursor) {
                return reward;
            }
        }
        return rewards.get(rewards.size() - 1);
    }

    private void executeReward(Player player, Reward reward) {
        for (String raw : reward.commands()) {
            if (raw == null || raw.isBlank()) {
                continue;
            }

            String expanded = raw
                    .replace("%player%", player.getName())
                    .replace("%uuid%", player.getUniqueId().toString())
                    .replace("%reward%", reward.id());

            try {
                if (expanded.regionMatches(true, 0, "PLAYER:", 0, 7)) {
                    player.performCommand(expanded.substring(7).trim());
                } else if (expanded.regionMatches(true, 0, "CONSOLE:", 0, 8)) {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), expanded.substring(8).trim());
                } else {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), expanded.trim());
                }
            } catch (RuntimeException error) {
                getLogger().warning("Reward command failed for " + reward.id() + ": " + error);
            }
        }
    }

    private long remainingCooldownSeconds() {
        long cooldownMillis = i("cooldown.seconds", 21600, 0, 604800) * 1000L;
        if (cooldownMillis <= 0L || lastRitualMillis <= 0L) {
            return 0L;
        }
        long remaining = cooldownMillis - (System.currentTimeMillis() - lastRitualMillis);
        return remaining <= 0L ? 0L : (remaining + 999L) / 1000L;
    }

    private void reloadRuntimeSettings() {
        triggerNormalized = normalize(getConfig().getString("trigger.phrase", "я тебя могну"));
        hideTriggerMessage = getConfig().getBoolean("trigger.hide-trigger-message", false);
    }

    private void loadCooldown() {
        if (!getConfig().getBoolean("cooldown.persist", true) || cooldownFile == null || !Files.isRegularFile(cooldownFile)) {
            return;
        }
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(cooldownFile)) {
            properties.load(input);
            lastRitualMillis = Long.parseLong(properties.getProperty("lastRitualMillis", "0"));
        } catch (IOException | NumberFormatException error) {
            getLogger().warning("Could not load cooldown.properties: " + error.getMessage());
        }
    }

    private void saveCooldown() {
        if (!getConfig().getBoolean("cooldown.persist", true) || cooldownFile == null) {
            return;
        }
        try {
            Files.createDirectories(cooldownFile.getParent());
            Properties properties = new Properties();
            properties.setProperty("lastRitualMillis", Long.toString(lastRitualMillis));
            try (OutputStream output = Files.newOutputStream(cooldownFile)) {
                properties.store(output, "MogRitual persistent cooldown");
            }
        } catch (IOException error) {
            getLogger().warning("Could not save cooldown.properties: " + error.getMessage());
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("mogchance")) {
            List<Reward> rewards = loadRewards();
            sender.sendMessage(color("&d&lMogRitual &f— отображаемые шансы:"));
            for (Reward reward : rewards) {
                sender.sendMessage(color("&7- &f" + reward.displayName() + ": &d" + cleanNumber(reward.chance()) + "%"));
            }
            return true;
        }

        if (!command.getName().equalsIgnoreCase("mogritual")) {
            return false;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            sender.sendMessage(color("&dMogRitual: &f" + (ritualActive ? "ритуал идёт" : "ожидание")));
            sender.sendMessage(color("&7Участники: &f" + pending.size() + "/" + i("trigger.required-players", 3, 2, 8)));
            sender.sendMessage(color("&7Cooldown: &f" + formatDuration(remainingCooldownSeconds())));
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("mogritual.admin")) {
                sender.sendMessage(color("&cНет прав."));
                return true;
            }
            reloadConfig();
            reloadRuntimeSettings();
            sender.sendMessage(message("messages.reloaded", "&aMogRitual config перезагружен."));
            return true;
        }

        sender.sendMessage(color("&eИспользование: /mogritual [status|reload]"));
        return true;
    }

    private String message(String path, String fallback) {
        return color(getConfig().getString(path, fallback));
    }

    private String formatDuration(long seconds) {
        if (seconds <= 0L) {
            return "0 сек.";
        }
        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        long secs = seconds % 60L;
        if (hours > 0L) {
            return hours + "ч " + minutes + "м";
        }
        if (minutes > 0L) {
            return minutes + "м " + secs + "с";
        }
        return secs + "с";
    }

    private int i(String path, int fallback, int min, int max) {
        return Math.max(min, Math.min(max, getConfig().getInt(path, fallback)));
    }

    private double d(String path, double fallback, double min, double max) {
        double value = getConfig().getDouble(path, fallback);
        if (!Double.isFinite(value)) {
            value = fallback;
        }
        return Math.max(min, Math.min(max, value));
    }

    private String cleanNumber(double value) {
        if (Math.rint(value) == value) {
            return Long.toString((long)value);
        }
        return Double.toString(value);
    }

    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    private record Reward(String id, String displayName, double weight, double chance, List<String> commands) {}
}
