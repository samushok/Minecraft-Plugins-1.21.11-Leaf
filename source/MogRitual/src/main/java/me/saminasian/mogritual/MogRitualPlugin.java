package me.saminasian.mogritual;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import net.minecraft.network.protocol.game.ClientboundSetCameraPacket;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

public final class MogRitualPlugin extends JavaPlugin implements Listener {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final UUID RESOURCE_PACK_ID = UUID.fromString("6f147947-5191-4cc8-a7f6-7613ed4ee81b");
    private final LinkedHashMap<UUID, Long> pending = new LinkedHashMap<>();
    private final Map<UUID, Long> playerCooldowns = new HashMap<>();
    private final Map<UUID, PlayerResourcePackStatusEvent.Status> resourcePackStatuses = new HashMap<>();
    private volatile String triggerNormalized = "я тебя могну";
    private volatile boolean generalEnabled = true;
    private volatile boolean hideTriggerMessage = false;
    private volatile boolean acceptCancelledChat = true;
    private volatile boolean ignoreCase = true;
    private volatile boolean normalizeSpaces = true;
    private volatile boolean stripEndingPunctuation = true;
    private boolean ritualActive = false;
    private long globalCooldownMillis = 0L;
    private long guardianCacheUntilMillis = 0L;
    private double guardianCachedMultiplier = 1.0;
    private DanceSession activeDanceSession;
    private Path cooldownFile;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        migrateConfig();
        reloadRuntimeSettings();
        cooldownFile = getDataFolder().toPath().resolve("cooldown.properties");
        loadCooldown();
        Bukkit.getPluginManager().registerEvents(this, this);

        List<String> warnings = validateConfig();
        if (warnings.isEmpty()) {
            getLogger().info("MogRitual configuration validation passed.");
        } else {
            for (String warning : warnings) {
                getLogger().warning("Config: " + warning);
            }
        }
        getLogger().info("MogRitual enabled for Leaf / Paper 1.21.11");
    }

    @Override
    public void onDisable() {
        cleanupDance(activeDanceSession);
        saveCooldown();
        pending.clear();
        resourcePackStatuses.clear();
        ritualActive = false;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!getConfig().getBoolean("resource-pack.enabled", false)
                || !getConfig().getBoolean("resource-pack.send-on-join", true)) {
            return;
        }

        int delay = i("resource-pack.join-delay-ticks", 40, 0, 400);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            Player player = event.getPlayer();
            if (player.isOnline()) {
                sendConfiguredResourcePack(player, false);
            }
        }, delay);
    }

    @EventHandler
    public void onResourcePackStatus(PlayerResourcePackStatusEvent event) {
        if (!RESOURCE_PACK_ID.equals(event.getID())) {
            return;
        }

        resourcePackStatuses.put(event.getPlayer().getUniqueId(), event.getStatus());
        switch (event.getStatus()) {
            case SUCCESSFULLY_LOADED -> tell(event.getPlayer(),
                    message("messages.pack-loaded", "&d[MOG] &aРесурс-пак ритуала загружен."));
            case DECLINED, FAILED_DOWNLOAD, FAILED_RELOAD, INVALID_URL, DISCARDED -> tell(event.getPlayer(),
                    message("messages.pack-failed", "&d[MOG] &cНе удалось загрузить ресурс-пак ритуала."));
            default -> {
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onChat(AsyncChatEvent event) {
        if (!generalEnabled || (event.isCancelled() && !acceptCancelledChat)) {
            return;
        }

        String plain = PlainTextComponentSerializer.plainText().serialize(event.message());
        String message = normalize(plain);
        if (triggerNormalized.isBlank() || !message.equals(triggerNormalized)) {
            return;
        }

        if (hideTriggerMessage) {
            event.setCancelled(true);
        }

        UUID playerId = event.getPlayer().getUniqueId();
        Bukkit.getScheduler().runTask(this, () -> handleTrigger(playerId));
    }

    private boolean hasLoadedRitualPack(Player player) {
        return resourcePackStatuses.get(player.getUniqueId())
                == PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED;
    }

    private boolean resourcePackConfigured() {
        if (!getConfig().getBoolean("resource-pack.enabled", false)) {
            return false;
        }
        String url = getConfig().getString("resource-pack.url", "");
        String sha1 = getConfig().getString("resource-pack.sha1", "");
        return url != null && !url.isBlank()
                && sha1 != null && sha1.matches("(?i)[0-9a-f]{40}");
    }

    private void sendConfiguredResourcePack(Player player, boolean notify) {
        if (!resourcePackConfigured()) {
            if (notify) {
                tell(player, message(
                        "messages.pack-not-configured",
                        "&d[MOG] &cМузыкальный resource pack ещё не настроен администратором."
                ));
            }
            return;
        }

        String url = getConfig().getString("resource-pack.url", "");
        String sha1 = getConfig().getString("resource-pack.sha1", "").toLowerCase(Locale.ROOT);
        boolean required = getConfig().getBoolean("resource-pack.required", true);
        String prompt = getConfig().getString(
                "resource-pack.prompt",
                "&dНужен музыкальный pack для ритуала «Я тебя могну»."
        );

        resourcePackStatuses.remove(player.getUniqueId());
        try {
            player.setResourcePack(
                    RESOURCE_PACK_ID,
                    url,
                    sha1,
                    component(prompt),
                    required
            );
            if (notify) {
                tell(player, message(
                        "messages.pack-sent",
                        "&d[MOG] &fResource pack отправлен. Дождись его загрузки и повтори фразу."
                ));
            }
        } catch (IllegalArgumentException error) {
            getLogger().warning("Could not send ritual resource pack to " + player.getName() + ": " + error.getMessage());
            if (notify) {
                tell(player, message(
                        "messages.pack-failed",
                        "&d[MOG] &cНе удалось отправить resource pack ритуала."
                ));
            }
        }
    }

    private void handleTrigger(UUID playerId) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            return;
        }

        cleanupPending();

        if (getConfig().getBoolean("resource-pack.require-for-ritual", false)
                && !hasLoadedRitualPack(player)) {
            if (!resourcePackConfigured()) {
                tell(player, message(
                        "messages.pack-not-configured",
                        "&d[MOG] &cМузыкальный resource pack ещё не настроен администратором."
                ));
            } else {
                sendConfiguredResourcePack(player, true);
                tell(player, message(
                        "messages.pack-required",
                        "&d[MOG] &eДля ритуала сначала нужно загрузить музыкальный resource pack."
                ));
            }
            return;
        }

        if (!isWorldAllowed(player.getWorld().getName())) {
            tell(player, message("messages.world-blocked", "&d[MOG] &cВ этом мире ритуал отключён."));
            return;
        }

        if (getConfig().getBoolean("trigger.require-permission", false)) {
            String permission = getConfig().getString("trigger.permission-node", "mogritual.use");
            if (permission != null && !permission.isBlank() && !player.hasPermission(permission)) {
                tell(player, message("messages.no-permission", "&d[MOG] &cУ тебя нет доступа к этому ритуалу."));
                return;
            }
        }

        if (ritualActive) {
            tell(player, message("messages.busy", "&d[MOG] &eРитуал уже идёт."));
            return;
        }

        long remaining = remainingCooldownSeconds(player);
        if (remaining > 0L) {
            tell(player, message("messages.cooldown", "&d[MOG] &eРитуал снова будет доступен через &f%time%.")
                    .replace("%time%", formatDuration(remaining)));
            return;
        }

        if (pending.containsKey(playerId)) {
            tell(player, message("messages.already-joined", "&d[MOG] &7Ты уже участвуешь в текущем сборе."));
            return;
        }

        double gatherRadius = d("trigger.gather-radius", 12.0, 1.0, 64.0);
        Player anchor = firstPendingPlayer();
        if (anchor != null) {
            if (anchor.getWorld() != player.getWorld()
                    || anchor.getLocation().distanceSquared(player.getLocation()) > gatherRadius * gatherRadius) {
                tell(player, message("messages.too-far", "&d[MOG] &cНужно быть в радиусе %radius% блоков от остальных участников.")
                        .replace("%radius%", cleanNumber(gatherRadius)));
                return;
            }
        } else if (!pending.isEmpty()) {
            pending.clear();
        }

        pending.put(playerId, System.currentTimeMillis());

        int required = i("trigger.required-players", 2, 2, 8);
        int count = pending.size();
        String joined = message("messages.joined", "&d[MOG] &f%player% &7вошёл в ритуал. &f%count%/%required%")
                .replace("%player%", player.getName())
                .replace("%count%", String.valueOf(count))
                .replace("%required%", String.valueOf(required));

        for (UUID id : pending.keySet()) {
            Player participant = Bukkit.getPlayer(id);
            if (participant != null && participant.isOnline()) {
                tell(participant, joined);
            }
        }

        if (count < required) {
            int missing = required - count;
            int windowSeconds = i("trigger.window-seconds", 12, 5, 120);

            String waitTitle = getConfig().getString(
                    "trigger.waiting-title",
                    "&d&lВам нужен ещё %missing% игрок для ритуала"
            );
            String waitSubtitle = getConfig().getString(
                    "trigger.waiting-subtitle",
                    "&fУ вас %seconds% секунд"
            );

            showTitle(
                    player,
                    waitTitle == null ? "" : waitTitle.replace("%missing%", String.valueOf(missing)),
                    waitSubtitle == null ? "" : waitSubtitle.replace("%seconds%", String.valueOf(windowSeconds)),
                    5,
                    45,
                    10
            );

            final UUID waitingPlayer = playerId;
            final long joinedAt = pending.getOrDefault(playerId, System.currentTimeMillis());
            Bukkit.getScheduler().runTaskLater(this, () -> {
                Long currentJoin = pending.get(waitingPlayer);
                if (currentJoin == null || currentJoin.longValue() != joinedAt || ritualActive) {
                    return;
                }

                long expiryMillis = joinedAt + windowSeconds * 1000L;
                if (System.currentTimeMillis() + 100L < expiryMillis) {
                    return;
                }

                pending.remove(waitingPlayer);
                Player stillOnline = Bukkit.getPlayer(waitingPlayer);
                if (stillOnline != null && stillOnline.isOnline()) {
                    String expiredTitle = getConfig().getString(
                            "trigger.expired-title",
                            "&c&lВремя ритуала истекло"
                    );
                    String expiredSubtitle = getConfig().getString(
                            "trigger.expired-subtitle",
                            "&7Нужен ещё один игрок"
                    );
                    showTitle(stillOnline, expiredTitle, expiredSubtitle, 5, 35, 10);
                    tell(stillOnline, message(
                            "messages.wait-expired",
                            "&d[MOG] &7Второй игрок не успел присоединиться за %seconds% секунд."
                    ).replace("%seconds%", String.valueOf(windowSeconds)));
                }
            }, windowSeconds * 20L);

            return;
        }

        List<Player> participants = new ArrayList<>();
        for (UUID id : pending.keySet()) {
            Player participant = Bukkit.getPlayer(id);
            if (participant != null && participant.isOnline() && remainingCooldownSeconds(participant) <= 0L) {
                participants.add(participant);
            }
            if (participants.size() >= required) {
                break;
            }
        }
        pending.clear();

        if (participants.size() < required || !participantsWithinRadius(participants, gatherRadius)) {
            for (Player participant : participants) {
                tell(participant, message("messages.ritual-abort",
                        "&d[MOG] &cРитуал отменён: один из участников вышел, ушёл слишком далеко или получил cooldown."));
            }
            return;
        }

        startRitual(participants);
    }

    private void cleanupPending() {
        long cutoff = System.currentTimeMillis() - i("trigger.window-seconds", 12, 5, 120) * 1000L;
        pending.entrySet().removeIf(entry -> {
            Player player = Bukkit.getPlayer(entry.getKey());
            return entry.getValue() < cutoff
                    || player == null
                    || !player.isOnline()
                    || !isWorldAllowed(player.getWorld().getName())
                    || remainingCooldownSeconds(player) > 0L;
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

    private boolean isWorldAllowed(String worldName) {
        List<String> allowed = getConfig().getStringList("trigger.allowed-worlds");
        if (!allowed.isEmpty() && allowed.stream().noneMatch(name -> name.equalsIgnoreCase(worldName))) {
            return false;
        }
        List<String> blocked = getConfig().getStringList("trigger.blocked-worlds");
        return blocked.stream().noneMatch(name -> name.equalsIgnoreCase(worldName));
    }

    private void startRitual(List<Player> participants) {
        List<Reward> rewards = loadRewards();
        if (rewards.isEmpty()) {
            for (Player participant : participants) {
                tell(participant, message("messages.no-rewards", "&d[MOG] &cВ config.yml нет доступных наград."));
            }
            return;
        }

        World world = participants.get(0).getWorld();
        Location center = averageLocation(participants);
        ritualActive = true;
        final boolean cooldownAppliedAtStart = cooldownStartsAtStart();

        if (cooldownAppliedAtStart) {
            markCooldown(participants);
        }

        String startMessage = message("messages.ritual-start",
                "&d&l[MOG] &fДва игрока завершили фразу. Ритуал начинается...");
        String ritualSubtitle = getConfig().getString("ritual.subtitle", "&fКинематографический ритуал начинается...");
        for (Player participant : participants) {
            tell(participant, startMessage);
            if (getConfig().getBoolean("ritual.dance.text.title-enabled", true)) {
                showTitle(participant, danceTextComponent(), component(ritualSubtitle), 5, 30, 8);
            } else {
                showTitle(participant, getConfig().getString("ritual.title", "&d&lЯ ТЕБЯ МОГНУ"), ritualSubtitle, 5, 30, 8);
            }
        }

        playCustomSound(participants, center);
        final DanceSession dance = startDance(participants);
        activeDanceSession = dance;
        activateDanceCameras(participants, dance);

        final int duration = i("ritual.duration-ticks", 100, 20, 400);
        final int interval = i("ritual.update-interval-ticks", 2, 1, 20);
        final double maxDrift = d("trigger.max-distance-during-ritual", 24.0, 2.0, 96.0);

        new BukkitRunnable() {
            private int elapsed = 0;

            @Override
            public void run() {
                if (!participantsStillValid(participants, world, center, maxDrift)) {
                    cleanupDance(dance);
                    abortRitual(participants);
                    cancel();
                    return;
                }

                if (elapsed >= duration) {
                    cleanupDance(dance);
                    cancel();
                    startRoulette(participants, rewards, cooldownAppliedAtStart);
                    return;
                }

                renderDance(participants, dance, elapsed);
                renderRitual(world, center, participants, elapsed);
                playVanillaRitualSound(world, center, elapsed, duration);

                elapsed += interval;
            }
        }.runTaskTimer(this, 0L, interval);
    }

    private DanceSession startDance(List<Player> participants) {
        if (!getConfig().getBoolean("ritual.dance.enabled", true)) {
            return new DanceSession(List.of(), Map.of(), Map.of());
        }

        List<BlockDisplay> blocks = new ArrayList<>();
        Map<UUID, BlockDisplay> cameras = new HashMap<>();
        Map<UUID, DancePlayerState> playerStates = new HashMap<>();
        List<String> materialNames = getConfig().getStringList("ritual.dance.blocks.materials");
        if (materialNames.isEmpty()) {
            materialNames = List.of("AMETHYST_BLOCK", "PURPUR_BLOCK", "SEA_LANTERN");
        }

        for (Player participant : participants) {
            Location original = participant.getLocation().clone();
            playerStates.put(participant.getUniqueId(), new DancePlayerState(
                    participant.isSneaking(),
                    original.getYaw(),
                    original.getPitch()
            ));

            World world = participant.getWorld();
            Location base = original.clone().add(0.0, 1.0, 0.0);

            for (int index = 0; index < 3; index++) {
                String configured = materialNames.get(index % materialNames.size());
                Material material = Material.matchMaterial(configured == null ? "" : configured);
                if (material == null || !material.isBlock()) {
                    material = index == 0 ? Material.AMETHYST_BLOCK
                            : index == 1 ? Material.PURPUR_BLOCK
                            : Material.SEA_LANTERN;
                }

                final Material blockMaterial = material;
                BlockDisplay display = world.spawn(base, BlockDisplay.class, entity -> {
                    entity.setBlock(blockMaterial.createBlockData());
                    entity.setInvulnerable(true);
                    entity.setPersistent(false);
                    entity.setTeleportDuration(i("ritual.update-interval-ticks", 2, 1, 20));
                    entity.setGlowing(getConfig().getBoolean("ritual.dance.blocks.glowing", true));
                });
                blocks.add(display);
            }

            if (getConfig().getBoolean("ritual.dance.camera.enabled", true)) {
                double cameraRadius = d("ritual.dance.camera.radius", 4.6, 2.0, 10.0);
                double cameraHeight = d("ritual.dance.camera.height", 2.0, 0.2, 6.0);
                Location cameraStart = original.clone().add(cameraRadius, cameraHeight, 0.0);
                BlockDisplay camera = world.spawn(cameraStart, BlockDisplay.class, entity -> {
                    entity.setBlock(Material.AIR.createBlockData());
                    entity.setInvulnerable(true);
                    entity.setPersistent(false);
                    entity.setTeleportDuration(i("ritual.dance.camera.teleport-duration-ticks", 2, 0, 20));
                    entity.setViewRange(1.5f);
                });
                cameras.put(participant.getUniqueId(), camera);
                updateCameraTransform(participant, camera, 0);
            }
        }

        return new DanceSession(blocks, cameras, playerStates);
    }

    private void activateDanceCameras(List<Player> participants, DanceSession dance) {
        if (dance == null || dance.cameras().isEmpty()) {
            return;
        }

        // Give the client one tick to receive the camera entity spawn packet first.
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!ritualActive || activeDanceSession != dance) {
                return;
            }
            for (Player participant : participants) {
                BlockDisplay camera = dance.cameras().get(participant.getUniqueId());
                if (camera != null && camera.isValid() && participant.isOnline()) {
                    setCamera(participant, camera);
                }
            }
        }, 2L);
    }

    private void setCamera(Player player, Entity cameraEntity) {
        try {
            net.minecraft.world.entity.Entity handle = ((CraftEntity)cameraEntity).getHandle();
            ((CraftPlayer)player).getHandle().connection.send(new ClientboundSetCameraPacket(handle));
        } catch (RuntimeException error) {
            getLogger().warning("Could not enable cinematic camera for " + player.getName() + ": " + error);
        }
    }

    private void restoreCamera(Player player) {
        try {
            var handle = ((CraftPlayer)player).getHandle();
            handle.connection.send(new ClientboundSetCameraPacket(handle));
        } catch (RuntimeException error) {
            getLogger().warning("Could not restore camera for " + player.getName() + ": " + error);
        }
    }

    private void updateCameraTransform(Player participant, BlockDisplay camera, int elapsed) {
        if (camera == null || !camera.isValid()) {
            return;
        }

        double radius = d("ritual.dance.camera.radius", 4.6, 2.0, 10.0);
        double height = d("ritual.dance.camera.height", 2.0, 0.2, 6.0);
        double targetHeight = d("ritual.dance.camera.target-height", 1.15, 0.0, 3.0);
        double bob = d("ritual.dance.camera.bob-amplitude", 0.20, 0.0, 1.0);
        double speed = d("ritual.dance.camera.orbit-speed-radians-per-tick", 0.045, 0.005, 0.20);

        double angle = elapsed * speed;
        Location base = participant.getLocation();
        Location cameraLocation = base.clone().add(
                Math.cos(angle) * radius,
                height + Math.sin(angle * 1.25) * bob,
                Math.sin(angle) * radius
        );

        Location target = base.clone().add(0.0, targetHeight, 0.0);
        Vector direction = target.toVector().subtract(cameraLocation.toVector());
        double horizontal = Math.sqrt(direction.getX() * direction.getX() + direction.getZ() * direction.getZ());
        float yaw = (float)Math.toDegrees(Math.atan2(-direction.getX(), direction.getZ()));
        float pitch = (float)Math.toDegrees(-Math.atan2(direction.getY(), horizontal));

        cameraLocation.setYaw(yaw);
        cameraLocation.setPitch(pitch);
        camera.teleport(cameraLocation);
        camera.setRotation(yaw, pitch);
    }

    private void renderDance(List<Player> participants, DanceSession dance, int elapsed) {
        if (dance == null || !getConfig().getBoolean("ritual.dance.enabled", true)) {
            return;
        }

        int interval = i("ritual.update-interval-ticks", 2, 1, 20);
        int step = elapsed / Math.max(1, interval);
        double radius = d("ritual.dance.blocks.radius", 1.75, 0.5, 4.0);
        double height = d("ritual.dance.blocks.height", 1.05, 0.0, 3.5);
        double bob = d("ritual.dance.blocks.bob-amplitude", 0.35, 0.0, 1.5);
        double rotationSpeed = d("ritual.dance.blocks.rotation-speed", 0.22, 0.01, 1.5);
        int particleRefresh = i("ritual.dance.particles.refresh-ticks", 4, 1, 20);
        boolean particleFrame = elapsed % particleRefresh == 0;
        int sparkCount = scaleCosmeticCount(i("ritual.dance.particles.spark-count-per-player", 5, 0, 30));
        int endRodCount = scaleCosmeticCount(i("ritual.dance.particles.end-rod-count-per-player", 3, 0, 20));
        float spinDegreesPerTick = (float)d("ritual.dance.player-animation.spin-degrees-per-tick", 2.6, 0.0, 12.0);

        for (int playerIndex = 0; playerIndex < participants.size(); playerIndex++) {
            Player participant = participants.get(playerIndex);
            if (!participant.isOnline()) {
                continue;
            }

            DancePlayerState state = dance.playerStates().get(participant.getUniqueId());

            // Actual player-model dance: alternating crouch, arm swings, and smooth body spin.
            if (getConfig().getBoolean("ritual.dance.player-animation.enabled", true)) {
                int poseBeatTicks = i("ritual.dance.player-animation.pose-beat-ticks", 8, 2, 40);
                boolean crouch = (elapsed / poseBeatTicks) % 2 == 0;
                participant.setSneaking(crouch);

                if (step % 2 == 0) {
                    participant.swingMainHand();
                } else {
                    participant.swingOffHand();
                }

                float startYaw = state == null ? participant.getLocation().getYaw() : state.yaw();
                participant.setRotation(startYaw + elapsed * spinDegreesPerTick, 0.0f);
            }

            if (getConfig().getBoolean("ritual.dance.text.enabled", true)) {
                participant.sendActionBar(danceTextComponent());
            }

            Location base = participant.getLocation().clone();
            World world = participant.getWorld();
            int offset = playerIndex * 3;
            for (int blockIndex = 0; blockIndex < 3; blockIndex++) {
                int listIndex = offset + blockIndex;
                if (listIndex >= dance.blocks().size()) {
                    break;
                }
                BlockDisplay display = dance.blocks().get(listIndex);
                if (!display.isValid()) {
                    continue;
                }

                double angle = elapsed * rotationSpeed + (Math.PI * 2.0 * blockIndex / 3.0);
                double y = height + Math.sin(angle * 1.7) * bob;
                Location target = base.clone().add(
                        Math.cos(angle) * radius,
                        y,
                        Math.sin(angle) * radius
                );
                target.setYaw((float)Math.toDegrees(angle) + 90.0f);
                display.teleport(target);
                display.setRotation(target.getYaw(), 0.0f);
            }

            BlockDisplay camera = dance.cameras().get(participant.getUniqueId());
            if (camera != null) {
                updateCameraTransform(participant, camera, elapsed);
            }

            if (particleFrame) {
                Location particles = base.clone().add(0.0, 1.05, 0.0);
                if (sparkCount > 0) {
                    world.spawnParticle(Particle.ELECTRIC_SPARK, particles, sparkCount,
                            radius * 0.65, 0.75, radius * 0.65, 0.03);
                }
                if (endRodCount > 0) {
                    world.spawnParticle(Particle.END_ROD, particles, endRodCount,
                            0.55, 0.75, 0.55, 0.015);
                }
            }
        }
    }

    private Component danceTextComponent() {
        String text = getConfig().getString("ritual.dance.text.value", "✦ Я ТЕБЯ МОГНУ ✦");
        if (text == null || text.isBlank()) {
            return Component.empty();
        }

        TextColor start = TextColor.fromHexString(
                getConfig().getString("ritual.dance.text.start-color", "#ff4fd8")
        );
        TextColor end = TextColor.fromHexString(
                getConfig().getString("ritual.dance.text.end-color", "#7c5cff")
        );
        if (start == null) start = TextColor.color(0xff4fd8);
        if (end == null) end = TextColor.color(0x7c5cff);

        boolean bold = getConfig().getBoolean("ritual.dance.text.bold", true);
        Component out = Component.empty();
        int length = Math.max(1, text.length() - 1);

        for (int index = 0; index < text.length(); index++) {
            double t = index / (double)length;
            int r = (int)Math.round(start.red() + (end.red() - start.red()) * t);
            int g = (int)Math.round(start.green() + (end.green() - start.green()) * t);
            int b = (int)Math.round(start.blue() + (end.blue() - start.blue()) * t);
            Component part = Component.text(String.valueOf(text.charAt(index)), TextColor.color(r, g, b));
            if (bold) {
                part = part.decorate(TextDecoration.BOLD);
            }
            out = out.append(part);
        }
        return out;
    }

    private void cleanupDance(DanceSession dance) {
        if (dance == null) {
            return;
        }

        for (Map.Entry<UUID, BlockDisplay> entry : dance.cameras().entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && player.isOnline()) {
                restoreCamera(player);
            }
        }

        for (BlockDisplay camera : dance.cameras().values()) {
            if (camera != null && camera.isValid()) {
                camera.remove();
            }
        }

        for (BlockDisplay display : dance.blocks()) {
            if (display != null && display.isValid()) {
                display.remove();
            }
        }

        for (Map.Entry<UUID, DancePlayerState> entry : dance.playerStates().entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && player.isOnline()) {
                DancePlayerState state = entry.getValue();
                player.setSneaking(state.sneaking());
                player.setRotation(state.yaw(), state.pitch());
            }
        }

        String customSound = getConfig().getString("ritual.sounds.custom-key", "");
        if (customSound != null && !customSound.isBlank()) {
            for (UUID id : dance.playerStates().keySet()) {
                Player player = Bukkit.getPlayer(id);
                if (player != null && player.isOnline()) {
                    player.stopSound(customSound.trim(), SoundCategory.PLAYERS);
                }
            }
        }

        if (activeDanceSession == dance) {
            activeDanceSession = null;
        }
    }

    private void renderRitual(World world, Location center, List<Player> participants, int elapsed) {
        if (!getConfig().getBoolean("ritual.particles.enabled", true)) {
            return;
        }

        int ringPoints = Math.max(4, scaleCosmeticCount(i("ritual.particles.ring-points", 12, 4, 48)));
        double ringRadius = d("ritual.particles.ring-radius", 2.6, 0.5, 8.0);
        double ringHeight = d("ritual.particles.ring-height", 0.25, -1.0, 4.0);
        double rotationSpeed = d("ritual.particles.rotation-speed", 0.08, 0.0, 0.5);
        double rotation = elapsed * rotationSpeed;

        for (int point = 0; point < ringPoints; point++) {
            double angle = rotation + Math.PI * 2.0 * point / ringPoints;
            Location particle = center.clone().add(
                    Math.cos(angle) * ringRadius,
                    ringHeight + (point % 2) * 0.15,
                    Math.sin(angle) * ringRadius
            );
            world.spawnParticle(Particle.END_ROD, particle, 1, 0.0, 0.0, 0.0, 0.0);
        }

        int enchant = scaleCosmeticCount(i("ritual.particles.center-enchant-count", 12, 0, 60));
        if (enchant > 0) {
            double spread = d("ritual.particles.center-spread", 1.6, 0.1, 5.0);
            world.spawnParticle(Particle.ENCHANT, center.clone().add(0.0, 1.0, 0.0),
                    enchant, spread, 1.0, spread, 0.03);
        }

        int witch = scaleCosmeticCount(i("ritual.particles.player-witch-count", 3, 0, 20));
        if (witch > 0) {
            for (Player participant : participants) {
                world.spawnParticle(Particle.WITCH, participant.getLocation().clone().add(0.0, 1.0, 0.0),
                        witch, 0.35, 0.65, 0.35, 0.01);
            }
        }
    }

    private void playVanillaRitualSound(World world, Location center, int elapsed, int duration) {
        if (!getConfig().getBoolean("ritual.sounds.vanilla-enabled", true)) {
            return;
        }

        String custom = getConfig().getString("ritual.sounds.custom-key", "");
        boolean withCustom = getConfig().getBoolean("ritual.sounds.vanilla-when-custom-present", false);
        if (custom != null && !custom.isBlank() && !withCustom) {
            return;
        }

        int soundInterval = i("ritual.sounds.vanilla-interval-ticks", 20, 5, 80);
        if (elapsed % soundInterval != 0) {
            return;
        }

        String key = getConfig().getString("ritual.sounds.vanilla-key", "minecraft:block.note_block.bass");
        if (key == null || key.isBlank()) {
            return;
        }

        float volume = (float)d("ritual.sounds.vanilla-volume", 0.8, 0.0, 4.0);
        float startPitch = (float)d("ritual.sounds.vanilla-start-pitch", 0.7, 0.01, 2.0);
        float endPitch = (float)d("ritual.sounds.vanilla-end-pitch", 1.5, 0.01, 2.0);
        float progress = Math.min(1.0f, elapsed / (float)Math.max(1, duration));
        float pitch = startPitch + (endPitch - startPitch) * progress;
        world.playSound(center, key, SoundCategory.PLAYERS, volume, pitch);
    }

    private void startRoulette(List<Player> participants, List<Reward> rewards, boolean cooldownAppliedAtStart) {
        Map<UUID, Reward> finals = chooseFinalRewards(participants, rewards);

        if (!getConfig().getBoolean("roulette.enabled", true)) {
            finishRoulette(participants, finals, cooldownAppliedAtStart);
            ritualActive = false;
            return;
        }

        final int duration = i("roulette.duration-ticks", 40, 12, 160);
        final int interval = i("roulette.update-interval-ticks", 4, 2, 20);
        final String title = getConfig().getString("roulette.title", "&d&lЯ ТЕБЯ МОГНУ");
        final String previewFormat = getConfig().getString("roulette.preview-format", "&7▶ &f%reward% &7◀");
        final String previewSound = getConfig().getString("roulette.preview-sound-key", "minecraft:block.note_block.pling");
        final float previewVolume = (float)d("roulette.preview-sound-volume", 0.35, 0.0, 4.0);
        final float pitchStart = (float)d("roulette.preview-pitch-start", 1.0, 0.01, 2.0);
        final float pitchEnd = (float)d("roulette.preview-pitch-end", 1.8, 0.01, 2.0);

        new BukkitRunnable() {
            private int elapsed = 0;

            @Override
            public void run() {
                if (elapsed >= duration) {
                    finishRoulette(participants, finals, cooldownAppliedAtStart);
                    ritualActive = false;
                    cancel();
                    return;
                }

                for (Player participant : participants) {
                    if (!participant.isOnline()) {
                        continue;
                    }
                    Reward preview = pickReward(rewards);
                    String subtitle = previewFormat == null ? preview.displayName()
                            : previewFormat.replace("%reward%", preview.displayName());
                    showTitle(participant, title, subtitle, 0, interval + 2, 0);

                    if (previewSound != null && !previewSound.isBlank() && previewVolume > 0.0f) {
                        float progress = Math.min(1.0f, elapsed / (float)Math.max(1, duration));
                        float pitch = pitchStart + (pitchEnd - pitchStart) * progress;
                        participant.playSound(participant.getLocation(), previewSound,
                                SoundCategory.PLAYERS, previewVolume, pitch);
                    }
                }

                elapsed += interval;
            }
        }.runTaskTimer(this, 0L, interval);
    }

    private Map<UUID, Reward> chooseFinalRewards(List<Player> participants, List<Reward> rewards) {
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
        return finals;
    }

    private void finishRoulette(List<Player> participants, Map<UUID, Reward> finals, boolean cooldownAppliedAtStart) {
        boolean execute = getConfig().getBoolean("roulette.execute-reward-commands", true);
        boolean broadcast = getConfig().getBoolean("roulette.broadcast-winners", false);
        String finalTitle = getConfig().getString("roulette.final-title", "&d&lЯ ТЕБЯ МОГНУ");
        String finalSubtitle = getConfig().getString("roulette.final-subtitle", "&aВыпало: &f%reward%");
        String spectatorTitle = getConfig().getString("roulette.spectator-title", "&d&lЯ ТЕБЯ МОГНУ");
        String spectatorSubtitle = getConfig().getString("roulette.spectator-subtitle", "&7Сегодня без награды");
        String finalSound = getConfig().getString("roulette.final-sound-key", "minecraft:entity.player.levelup");
        float finalVolume = (float)d("roulette.final-sound-volume", 1.0, 0.0, 4.0);
        float finalPitch = (float)d("roulette.final-sound-pitch", 1.1, 0.01, 2.0);

        List<UUID> failedRewardPlayers = new ArrayList<>();

        for (Player participant : participants) {
            Reward reward = finals.get(participant.getUniqueId());

            if (reward == null) {
                if (participant.isOnline()) {
                    tell(participant, message("messages.spectator",
                            "&d[MOG] &7В режиме ONE_RANDOM награду получил другой участник."));
                    showTitle(participant, spectatorTitle, spectatorSubtitle, 5, 35, 10);
                }
                continue;
            }

            boolean rewardSucceeded = !execute || executeReward(participant, reward);
            if (!rewardSucceeded) {
                failedRewardPlayers.add(participant.getUniqueId());
                if (participant.isOnline()) {
                    tell(participant, message("messages.reward-failed",
                            "&d[MOG] &cНе удалось выдать награду. Cooldown не применён; сообщи администратору."));
                }
                getLogger().warning("Reward delivery failed for " + participant.getName()
                        + " (" + reward.id() + "); success cooldown was not applied to this player.");
                continue;
            }

            if (participant.isOnline()) {
                tell(participant, message("messages.reward", "&d[MOG] &fТебе выпало: &a%reward%")
                        .replace("%reward%", reward.displayName()));
                showTitle(participant, finalTitle,
                        finalSubtitle == null ? reward.displayName()
                                : finalSubtitle.replace("%reward%", reward.displayName()),
                        5, 50, 15);
                if (finalSound != null && !finalSound.isBlank() && finalVolume > 0.0f) {
                    participant.playSound(participant.getLocation(), finalSound,
                            SoundCategory.PLAYERS, finalVolume, finalPitch);
                }
            }

            if (broadcast) {
                String line = message("messages.broadcast-win", "&d[MOG] &f%player% получил &a%reward%&f!")
                        .replace("%player%", participant.getName())
                        .replace("%reward%", reward.displayName());
                for (Player online : Bukkit.getOnlinePlayers()) {
                    tell(online, line);
                }
                tell(Bukkit.getConsoleSender(), line);
            }
        }

        if (!cooldownAppliedAtStart) {
            if (cooldownScopeGlobal()) {
                if (failedRewardPlayers.isEmpty() && !participants.isEmpty()) {
                    markCooldown(participants);
                } else if (!failedRewardPlayers.isEmpty()) {
                    getLogger().warning("Global SUCCESS cooldown was skipped because at least one reward command failed.");
                }
            } else {
                List<Player> cooldownParticipants = new ArrayList<>();
                for (Player participant : participants) {
                    if (!failedRewardPlayers.contains(participant.getUniqueId())) {
                        cooldownParticipants.add(participant);
                    }
                }
                if (!cooldownParticipants.isEmpty()) {
                    markCooldown(cooldownParticipants);
                }
            }
        }
    }

    private void abortRitual(List<Player> participants) {
        ritualActive = false;
        for (Player participant : participants) {
            if (participant.isOnline()) {
                tell(participant, message("messages.ritual-abort",
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
        double x = 0.0;
        double y = 0.0;
        double z = 0.0;
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
            if (reward == null || !reward.getBoolean("enabled", true)) {
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
        double total = rewards.stream().mapToDouble(Reward::weight).sum();
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

    private boolean executeReward(Player player, Reward reward) {
        boolean attempted = false;

        for (String raw : reward.commands()) {
            if (raw == null || raw.isBlank()) {
                continue;
            }

            attempted = true;
            String expanded = raw
                    .replace("%player%", player.getName())
                    .replace("%uuid%", player.getUniqueId().toString())
                    .replace("%reward%", reward.id());

            try {
                boolean success;
                if (expanded.regionMatches(true, 0, "PLAYER:", 0, 7)) {
                    if (!player.isOnline()) {
                        getLogger().warning("Skipped PLAYER reward command for offline player " + player.getName());
                        return false;
                    }
                    success = player.performCommand(expanded.substring(7).trim());
                } else if (expanded.regionMatches(true, 0, "CONSOLE:", 0, 8)) {
                    success = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), expanded.substring(8).trim());
                } else {
                    success = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), expanded.trim());
                }

                if (!success) {
                    getLogger().warning("Reward command returned false for " + reward.id()
                            + " and player " + player.getName() + ": " + expanded);
                    return false;
                }
            } catch (RuntimeException error) {
                getLogger().warning("Reward command failed for " + reward.id() + ": " + error);
                return false;
            }
        }

        if (!attempted) {
            getLogger().warning("Reward " + reward.id() + " has no executable commands.");
        }
        return attempted;
    }

    private long remainingCooldownSeconds(Player player) {
        if (player != null) {
            String bypass = getConfig().getString("cooldown.bypass-permission", "mogritual.cooldown.bypass");
            if (bypass != null && !bypass.isBlank() && player.hasPermission(bypass)) {
                return 0L;
            }
        }

        long cooldownMillis = i("cooldown.seconds", 21600, 0, 604800) * 1000L;
        if (cooldownMillis <= 0L) {
            return 0L;
        }

        long timestamp;
        if (cooldownScopeGlobal()) {
            timestamp = globalCooldownMillis;
        } else {
            timestamp = player == null ? 0L : playerCooldowns.getOrDefault(player.getUniqueId(), 0L);
        }

        long remaining = cooldownMillis - (System.currentTimeMillis() - timestamp);
        return timestamp <= 0L || remaining <= 0L ? 0L : (remaining + 999L) / 1000L;
    }

    private boolean cooldownScopeGlobal() {
        return "GLOBAL".equalsIgnoreCase(getConfig().getString("cooldown.scope", "PLAYER"));
    }

    private boolean cooldownStartsAtStart() {
        return "START".equalsIgnoreCase(getConfig().getString("cooldown.start", "SUCCESS"));
    }

    private void markCooldown(List<Player> participants) {
        long now = System.currentTimeMillis();
        if (cooldownScopeGlobal()) {
            globalCooldownMillis = now;
        } else {
            String bypass = getConfig().getString("cooldown.bypass-permission", "mogritual.cooldown.bypass");
            for (Player participant : participants) {
                if (bypass != null && !bypass.isBlank() && participant.hasPermission(bypass)) {
                    continue;
                }
                playerCooldowns.put(participant.getUniqueId(), now);
            }
        }
        saveCooldown();
    }

    private void migrateConfig() {
        int version = getConfig().getInt("config-version", 1);
        if (version < 3) {
            // v3 intentionally enables cancelled-chat compatibility so the ritual
            // still sees messages on servers where a chat-controller cancels the
            // vanilla/Paper chat event and renders the message itself.
            getConfig().set("trigger.accept-cancelled-chat", true);
            getConfig().set("config-version", 3);
            saveConfig();
            reloadConfig();
            version = 3;
            getLogger().info("Migrated MogRitual config to v3: chat-controller compatibility enabled.");
        }

        if (version < 4) {
            setIfMissing("ritual.dance.enabled", true);
            setIfMissing("ritual.dance.lyric-actionbar", "&d&l♪ Я тебя могну ♪");
            setIfMissing("ritual.dance.blocks.materials", List.of(
                    "AMETHYST_BLOCK",
                    "PURPUR_BLOCK",
                    "SEA_LANTERN"
            ));
            setIfMissing("ritual.dance.blocks.radius", 1.75);
            setIfMissing("ritual.dance.blocks.height", 1.05);
            setIfMissing("ritual.dance.blocks.bob-amplitude", 0.35);
            setIfMissing("ritual.dance.blocks.rotation-speed", 0.22);
            setIfMissing("ritual.dance.blocks.glowing", true);
            setIfMissing("ritual.dance.player-animation.enabled", true);
            setIfMissing("ritual.dance.particles.spark-count-per-player", 5);
            setIfMissing("ritual.dance.particles.end-rod-count-per-player", 3);

            // Upgrade only the untouched old vanilla fallback. Custom sound keys
            // and manually tuned vanilla settings are preserved.
            String oldVanillaKey = getConfig().getString(
                    "ritual.sounds.vanilla-key",
                    "minecraft:block.note_block.bass"
            );
            int oldVanillaInterval = getConfig().getInt("ritual.sounds.vanilla-interval-ticks", 20);
            if ("minecraft:block.note_block.bass".equalsIgnoreCase(oldVanillaKey)
                    && oldVanillaInterval == 20) {
                getConfig().set("ritual.sounds.vanilla-key", "minecraft:block.note_block.harp");
                getConfig().set("ritual.sounds.vanilla-interval-ticks", 5);
                getConfig().set("ritual.sounds.vanilla-volume", 0.65);
                getConfig().set("ritual.sounds.vanilla-start-pitch", 0.75);
                getConfig().set("ritual.sounds.vanilla-end-pitch", 1.65);
            }

            getConfig().set("config-version", 4);
            saveConfig();
            reloadConfig();
            version = 4;
            getLogger().info("Migrated MogRitual config to v4: dance visuals added.");
        }

        if (version < 5) {
            setIfMissing("ritual.duration-ticks", 100);
            setIfMissing("ritual.update-interval-ticks", 2);

            setIfMissing("ritual.dance.camera.enabled", true);
            setIfMissing("ritual.dance.camera.radius", 4.6);
            setIfMissing("ritual.dance.camera.height", 2.0);
            setIfMissing("ritual.dance.camera.target-height", 1.15);
            setIfMissing("ritual.dance.camera.bob-amplitude", 0.20);
            setIfMissing("ritual.dance.camera.orbit-speed-radians-per-tick", 0.045);
            setIfMissing("ritual.dance.camera.teleport-duration-ticks", 2);

            setIfMissing("ritual.dance.player-animation.spin-degrees-per-tick", 2.6);
            setIfMissing("ritual.dance.player-animation.pose-beat-ticks", 8);

            setIfMissing("ritual.dance.text.enabled", true);
            setIfMissing("ritual.dance.text.title-enabled", true);
            setIfMissing("ritual.dance.text.value", "✦ Я ТЕБЯ МОГНУ ✦");
            setIfMissing("ritual.dance.text.start-color", "#ff4fd8");
            setIfMissing("ritual.dance.text.end-color", "#7c5cff");
            setIfMissing("ritual.dance.text.bold", true);

            setIfMissing("ritual.dance.particles.refresh-ticks", 4);

            String customKey = getConfig().getString("ritual.sounds.custom-key", "");
            if (customKey == null || customKey.isBlank()) {
                getConfig().set("ritual.sounds.custom-key", "mogritual:ya_tebya_mognu");
                getConfig().set("ritual.sounds.custom-volume", 1.0);
                getConfig().set("ritual.sounds.custom-pitch", 1.0);
                getConfig().set("ritual.sounds.vanilla-when-custom-present", false);
            }

            getConfig().set("config-version", 5);
            saveConfig();
            reloadConfig();
            getLogger().info("Migrated MogRitual config to v5: cinematic camera and ritual audio added.");
            version = 5;
        }

        if (version < 6) {
            setIfMissing("resource-pack.enabled", false);
            setIfMissing("resource-pack.url", "");
            setIfMissing("resource-pack.sha1", "");
            setIfMissing("resource-pack.required", true);
            setIfMissing("resource-pack.require-for-ritual", false);
            setIfMissing("resource-pack.send-on-join", true);
            setIfMissing("resource-pack.join-delay-ticks", 40);
            setIfMissing(
                    "resource-pack.prompt",
                    "&dМузыкальный pack нужен для cinematic-ритуала «Я тебя могну»."
            );

            getConfig().set("config-version", 6);
            saveConfig();
            reloadConfig();
            getLogger().info("Migrated MogRitual config to v6: managed ritual resource-pack support added.");
            version = 6;
        }

        if (version < 7) {
            // 1.4.2 shortens the stock cinematic clip from 7 seconds to 5 seconds.
            // Preserve custom timing, but migrate the untouched old default.
            if (getConfig().getInt("ritual.duration-ticks", 140) == 140) {
                getConfig().set("ritual.duration-ticks", 100);
            }

            getConfig().set("config-version", 7);
            saveConfig();
            reloadConfig();
            getLogger().info("Migrated MogRitual config to v7: default ritual duration synchronized to 5 seconds.");
        }
    }

    private void setIfMissing(String path, Object value) {
        // ignoreDefault=true: write the key into the user's real config file even
        // when the bundled default config already knows about this path.
        if (!getConfig().contains(path, true)) {
            getConfig().set(path, value);
        }
    }

    private void reloadRuntimeSettings() {
        generalEnabled = getConfig().getBoolean("general.enabled", true);
        ignoreCase = getConfig().getBoolean("trigger.ignore-case", true);
        normalizeSpaces = getConfig().getBoolean("trigger.normalize-spaces", true);
        stripEndingPunctuation = getConfig().getBoolean("trigger.strip-ending-punctuation", true);
        hideTriggerMessage = getConfig().getBoolean("trigger.hide-trigger-message", false);
        acceptCancelledChat = getConfig().getBoolean("trigger.accept-cancelled-chat", true);
        triggerNormalized = normalize(getConfig().getString("trigger.phrase", "я тебя могну"));
    }

    private void loadCooldown() {
        if (!getConfig().getBoolean("cooldown.persist", true) || cooldownFile == null || !Files.isRegularFile(cooldownFile)) {
            return;
        }

        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(cooldownFile)) {
            properties.load(input);
            globalCooldownMillis = parseLong(properties.getProperty("global", properties.getProperty("lastRitualMillis", "0")));
            for (String key : properties.stringPropertyNames()) {
                if (!key.startsWith("player.")) {
                    continue;
                }
                try {
                    UUID uuid = UUID.fromString(key.substring("player.".length()));
                    long value = parseLong(properties.getProperty(key));
                    if (value > 0L) {
                        playerCooldowns.put(uuid, value);
                    }
                } catch (IllegalArgumentException ignored) {
                    getLogger().warning("Ignored invalid cooldown key: " + key);
                }
            }
            pruneExpiredCooldowns();
        } catch (IOException | IllegalArgumentException error) {
            getLogger().warning("Could not load cooldown.properties; starting with an empty cooldown state: "
                    + error.getMessage());
        }
    }

    private void saveCooldown() {
        if (!getConfig().getBoolean("cooldown.persist", true) || cooldownFile == null) {
            return;
        }

        pruneExpiredCooldowns();
        try {
            Files.createDirectories(cooldownFile.getParent());
            Properties properties = new Properties();
            properties.setProperty("global", Long.toString(globalCooldownMillis));
            for (Map.Entry<UUID, Long> entry : playerCooldowns.entrySet()) {
                properties.setProperty("player." + entry.getKey(), Long.toString(entry.getValue()));
            }
            try (OutputStream output = Files.newOutputStream(cooldownFile)) {
                properties.store(output, "MogRitual persistent cooldowns");
            }
        } catch (IOException error) {
            getLogger().warning("Could not save cooldown.properties: " + error.getMessage());
        }
    }

    private void pruneExpiredCooldowns() {
        long cooldownMillis = i("cooldown.seconds", 21600, 0, 604800) * 1000L;
        if (cooldownMillis <= 0L) {
            playerCooldowns.clear();
            globalCooldownMillis = 0L;
            return;
        }

        long cutoff = System.currentTimeMillis() - cooldownMillis;
        playerCooldowns.entrySet().removeIf(entry -> entry.getValue() <= cutoff);
        if (globalCooldownMillis <= cutoff) {
            globalCooldownMillis = 0L;
        }
    }

    private long parseLong(String value) {
        try {
            return Long.parseLong(value == null ? "0" : value);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private List<String> validateConfig() {
        List<String> warnings = new ArrayList<>();

        if (triggerNormalized.isBlank()) {
            warnings.add("trigger.phrase is empty.");
        }

        int requiredRaw = getConfig().getInt("trigger.required-players", 2);
        if (requiredRaw < 2 || requiredRaw > 8) {
            warnings.add("trigger.required-players should be between 2 and 8.");
        }

        String scope = getConfig().getString("cooldown.scope", "PLAYER");
        if (!"PLAYER".equalsIgnoreCase(scope) && !"GLOBAL".equalsIgnoreCase(scope)) {
            warnings.add("cooldown.scope must be PLAYER or GLOBAL.");
        }

        String start = getConfig().getString("cooldown.start", "SUCCESS");
        if (!"SUCCESS".equalsIgnoreCase(start) && !"START".equalsIgnoreCase(start)) {
            warnings.add("cooldown.start must be SUCCESS or START.");
        }

        String mode = getConfig().getString("roulette.reward-mode", "EACH");
        if (!"EACH".equalsIgnoreCase(mode) && !"ONE_RANDOM".equalsIgnoreCase(mode)) {
            warnings.add("roulette.reward-mode must be EACH or ONE_RANDOM.");
        }

        if (getConfig().getBoolean("resource-pack.enabled", false)) {
            String packUrl = getConfig().getString("resource-pack.url", "");
            String packSha1 = getConfig().getString("resource-pack.sha1", "");
            if (packUrl == null || !(packUrl.startsWith("https://") || packUrl.startsWith("http://"))) {
                warnings.add("resource-pack.url must be an http(s) URL when resource-pack.enabled=true.");
            }
            if (packSha1 == null || !packSha1.matches("(?i)[0-9a-f]{40}")) {
                warnings.add("resource-pack.sha1 must be a 40-character SHA-1 when resource-pack.enabled=true.");
            }
        }
        if (getConfig().getBoolean("resource-pack.require-for-ritual", false)
                && !getConfig().getBoolean("resource-pack.enabled", false)) {
            warnings.add("resource-pack.require-for-ritual=true requires resource-pack.enabled=true.");
        }

        List<Reward> rewards = loadRewards();
        if (rewards.isEmpty()) {
            warnings.add("No enabled reward has a positive weight.");
        }

        for (Reward reward : rewards) {
            if (reward.commands().isEmpty()) {
                warnings.add("Reward '" + reward.id() + "' has no commands.");
            }
        }

        return warnings;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("mogchance")) {
            sendChanceList(sender);
            return true;
        }

        if (!command.getName().equalsIgnoreCase("mogritual")) {
            return false;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            tell(sender, "&dMogRitual: &f" + (ritualActive ? "ритуал идёт" : "ожидание"));
            tell(sender, "&7Участники сбора: &f" + pending.size() + "/" + i("trigger.required-players", 2, 2, 8));
            tell(sender, "&7Cooldown scope: &f" + getConfig().getString("cooldown.scope", "PLAYER"));
            if (sender instanceof Player player) {
                tell(sender, "&7Твой cooldown: &f" + formatDuration(remainingCooldownSeconds(player)));
                PlayerResourcePackStatusEvent.Status status = resourcePackStatuses.get(player.getUniqueId());
                tell(sender, "&7Music pack: &f" + (status == null ? "UNKNOWN" : status.name()));
            } else {
                tell(sender, "&7Global cooldown: &f" + formatDuration(remainingCooldownSeconds(null)));
            }
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("pack")) {
            if (sender instanceof Player player) {
                sendConfiguredResourcePack(player, true);
            } else {
                tell(sender, "&cЭта команда доступна только игроку.");
            }
            return true;
        }

        if (!sender.hasPermission("mogritual.admin")) {
            tell(sender, message("messages.no-admin-permission", "&cНет прав."));
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            reloadRuntimeSettings();
            List<String> warnings = validateConfig();
            tell(sender, message("messages.reloaded", "&aMogRitual config перезагружен."));
            tell(sender, warnings.isEmpty()
                    ? "&aConfig validation: OK"
                    : "&eConfig validation: " + warnings.size() + " warning(s). Используй /mogritual validate");
            return true;
        }

        if (args[0].equalsIgnoreCase("validate")) {
            List<String> warnings = validateConfig();
            if (warnings.isEmpty()) {
                tell(sender, "&aMogRitual config: OK. Enabled rewards: &f" + loadRewards().size());
            } else {
                tell(sender, "&eMogRitual config warnings: &f" + warnings.size());
                for (String warning : warnings) {
                    tell(sender, "&7- &e" + warning);
                }
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("resetcooldown")) {
            if (args.length < 2) {
                tell(sender, "&eИспользование: /mogritual resetcooldown <player|all>");
                return true;
            }

            if (args[1].equalsIgnoreCase("all")) {
                playerCooldowns.clear();
                globalCooldownMillis = 0L;
                saveCooldown();
                tell(sender, message("messages.cooldown-reset-all", "&aВсе MogRitual cooldown сброшены."));
                return true;
            }

            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                tell(sender, message("messages.player-not-found", "&cИгрок не найден онлайн."));
                return true;
            }

            playerCooldowns.remove(target.getUniqueId());
            if (cooldownScopeGlobal()) {
                globalCooldownMillis = 0L;
            }
            saveCooldown();
            tell(sender, message("messages.cooldown-reset-player", "&aCooldown игрока %player% сброшен.")
                    .replace("%player%", target.getName()));
            return true;
        }

        if (args[0].equalsIgnoreCase("clearpending")) {
            pending.clear();
            tell(sender, message("messages.pending-cleared", "&aСписок ожидающих участников очищен."));
            return true;
        }

        tell(sender, "&e/mogritual status|pack|reload|validate|resetcooldown <player|all>|clearpending");
        return true;
    }

    private void sendChanceList(CommandSender sender) {
        List<Reward> rewards = loadRewards();
        double totalWeight = rewards.stream().mapToDouble(Reward::weight).sum();
        String header = getConfig().getString("chance-command.header", "&d&lMogRitual &f— награды:");
        String line = getConfig().getString("chance-command.line",
                "&7- &f%reward%: &d%chance%% &8(weight %weight%, real %actual%%)");
        boolean showActual = getConfig().getBoolean("chance-command.show-actual-weight-percent", true);

        tell(sender, header);
        for (Reward reward : rewards) {
            double actual = totalWeight > 0.0 ? reward.weight() / totalWeight * 100.0 : 0.0;
            String rendered = line == null ? reward.displayName() : line
                    .replace("%reward%", reward.displayName())
                    .replace("%chance%", cleanNumber(reward.chance()))
                    .replace("%weight%", cleanNumber(reward.weight()))
                    .replace("%actual%", showActual ? cleanNumberRounded(actual, 6) : "-");
            tell(sender, rendered);
        }
    }

    private String message(String path, String fallback) {
        return getConfig().getString(path, fallback);
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

    private double cosmeticMultiplier() {
        long now = System.currentTimeMillis();
        if (now < guardianCacheUntilMillis) {
            return guardianCachedMultiplier;
        }

        double multiplier = 1.0;
        org.bukkit.plugin.Plugin guardian = Bukkit.getPluginManager().getPlugin("ServerGuardian");
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

    private int scaleCosmeticCount(int base) {
        if (base <= 0) {
            return 0;
        }
        double multiplier = cosmeticMultiplier();
        if (multiplier >= 0.999) {
            return base;
        }
        return Math.max(1, (int)Math.round(base * multiplier));
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

    private String cleanNumberRounded(double value, int decimals) {
        String formatted = String.format(Locale.ROOT, "%." + decimals + "f", value);
        return formatted.replaceFirst("\\.?0+$", "");
    }

    private String normalize(String text) {
        if (text == null) {
            return "";
        }

        String result = text.trim();
        if (normalizeSpaces) {
            result = result.replaceAll("\\s+", " ");
        }
        if (stripEndingPunctuation) {
            result = result.replaceAll("[!?.…,;:]+$", "").trim();
        }
        if (ignoreCase) {
            result = result.toLowerCase(Locale.ROOT);
        }
        return result;
    }

    private Component component(String text) {
        return LEGACY.deserialize(text == null ? "" : text);
    }

    private void tell(CommandSender sender, String text) {
        sender.sendMessage(component(text));
    }

    private void showTitle(Player player, String title, String subtitle, int fadeInTicks, int stayTicks, int fadeOutTicks) {
        showTitle(player, component(title), component(subtitle), fadeInTicks, stayTicks, fadeOutTicks);
    }

    private void showTitle(Player player, Component title, Component subtitle, int fadeInTicks, int stayTicks, int fadeOutTicks) {
        Title.Times times = Title.Times.times(
                Duration.ofMillis(Math.max(0, fadeInTicks) * 50L),
                Duration.ofMillis(Math.max(0, stayTicks) * 50L),
                Duration.ofMillis(Math.max(0, fadeOutTicks) * 50L)
        );
        player.showTitle(Title.title(title, subtitle, times));
    }

    private record DanceSession(
            List<BlockDisplay> blocks,
            Map<UUID, BlockDisplay> cameras,
            Map<UUID, DancePlayerState> playerStates
    ) {}

    private record DancePlayerState(boolean sneaking, float yaw, float pitch) {}

    private record Reward(String id, String displayName, double weight, double chance, List<String> commands) {}
}
