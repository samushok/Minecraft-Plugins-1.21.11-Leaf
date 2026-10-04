package me.saminasian.spheres;

import org.bukkit.*;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.*;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * One complete STORM singularity.
 *
 * Each cast owns all of its state: captured targets, position history,
 * display shards, gravity pulses, temporal fractures and cleanup.
 */
final class StormBlackHoleSession extends BukkitRunnable {
    private final StormSphere storm;
    private final SpheresPlugin host;
    private final UUID ownerId;
    private final World world;
    private final Location center;
    private final Vector planeNormal;
    private final Vector planeRight;
    private final Vector planeUp = new Vector(0.0, 1.0, 0.0);
    private final UUID sessionId = UUID.randomUUID();

    private final Map<UUID, TargetState> targets = new LinkedHashMap<>();
    private final List<Shard> shards = new ArrayList<>();

    private final int durationTicks;
    private final double radius;
    private final double coreRadius;
    private final double releaseRadius;
    private final int scanInterval;
    private final int visualInterval;

    private int ageTicks = 0;
    private boolean finished = false;

    StormBlackHoleSession(StormSphere storm, Player owner, Location center) {
        this.storm = storm;
        this.host = storm.getHost();
        this.ownerId = owner.getUniqueId();
        this.world = owner.getWorld();
        this.center = center.clone();

        Vector facing = owner.getLocation().getDirection().setY(0.0);
        if (facing.lengthSquared() < 0.0001) {
            facing = new Vector(0.0, 0.0, 1.0);
        } else {
            facing.normalize();
        }
        this.planeNormal = facing.clone();
        this.planeRight = new Vector(-facing.getZ(), 0.0, facing.getX()).normalize();

        this.durationTicks = secondsToTicks(d(
                "storm.black-hole.duration-seconds", 30.0, 2.0, 180.0
        ));
        this.radius = d("storm.black-hole.radius", 14.0, 3.0, 40.0);
        this.coreRadius = d("storm.black-hole.core-radius", 2.2, 0.75, 8.0);
        this.releaseRadius = radius * d(
                "storm.black-hole.targeting.release-radius-multiplier", 1.35, 1.05, 3.0
        );
        this.scanInterval = i("storm.black-hole.targeting.scan-interval-ticks", 4, 1, 40);
        this.visualInterval = i("storm.black-hole.visuals.refresh-ticks", 1, 1, 10);
    }

    UUID id() {
        return sessionId;
    }

    UUID ownerId() {
        return ownerId;
    }

    Location center() {
        return center.clone();
    }

    void startSession() {
        spawnShards();
        playSound("storm.black-hole.sounds.create", Sound.ENTITY_WITHER_SPAWN, center);

        Player owner = Bukkit.getPlayer(ownerId);
        if (owner != null && owner.isOnline()) {
            String title = cfg().getString("storm.black-hole.presentation.title", "&5&lСИНГУЛЯРНОСТЬ");
            String subtitle = cfg().getString(
                    "storm.black-hole.presentation.subtitle",
                    "&7Пространство начинает разрушаться..."
            );
            if (cfg().getBoolean("storm.black-hole.presentation.owner-title-enabled", true)) {
                owner.sendTitle(color(title), color(subtitle), 5, 35, 10);
            }
        }

        runTaskTimer(host, 0L, 1L);
    }

    void shutdown(boolean collapse) {
        if (finished) return;
        finish(collapse);
    }

    @Override
    public void run() {
        if (finished) {
            cancel();
            return;
        }

        Player owner = Bukkit.getPlayer(ownerId);
        boolean cancelWithoutOwner = cfg().getBoolean(
                "storm.black-hole.lifecycle.cancel-if-owner-unavailable", true
        );
        if (cancelWithoutOwner && (owner == null
                || !owner.isOnline()
                || owner.isDead()
                || owner.getWorld() != world)) {
            finish(false);
            cancel();
            return;
        }

        if (ageTicks >= durationTicks) {
            finish(cfg().getBoolean("storm.black-hole.collapse.enabled", true));
            cancel();
            return;
        }

        if (ageTicks % scanInterval == 0) {
            scanTargets();
        }

        updateAmbientSound();
        updatePulse();
        updateTimeFracture();
        updateTargets();
        updateCollapseCountdown();

        if (ageTicks % visualInterval == 0) {
            renderBlackHole();
            updateShards();
            renderRealityFractures();
        }

        ageTicks++;
    }

    // ==========================================================
    // TARGETING + GRAVITY
    // ==========================================================

    private void scanTargets() {
        boolean players = cfg().getBoolean("storm.black-hole.targeting.players", true);
        boolean mobs = cfg().getBoolean("storm.black-hole.targeting.mobs", false);
        boolean requirePvp = cfg().getBoolean("storm.black-hole.targeting.require-pvp", true);
        boolean excludeOwner = cfg().getBoolean("storm.black-hole.targeting.exclude-owner", true);

        Collection<Entity> nearby = world.getNearbyEntities(center, radius, radius, radius);
        for (Entity entity : nearby) {
            if (!(entity instanceof LivingEntity target)
                    || !target.isValid()
                    || target.isDead()
                    || target.isInvulnerable()) {
                continue;
            }

            if (excludeOwner && target.getUniqueId().equals(ownerId)) continue;
            if (target.getLocation().distanceSquared(center) > radius * radius) continue;

            if (target instanceof Player player) {
                if (!players) continue;
                if (player.getGameMode() == GameMode.CREATIVE
                        || player.getGameMode() == GameMode.SPECTATOR) {
                    continue;
                }
                if (requirePvp && !world.getPVP()) continue;
            } else if (!mobs) {
                continue;
            }

            UUID id = target.getUniqueId();
            if (targets.containsKey(id)) continue;
            if (!storm.tryLockTarget(id)) continue;

            TargetState state = new TargetState(id);
            state.record(target.getLocation());
            targets.put(id, state);

            if (target instanceof Player player
                    && cfg().getBoolean("storm.black-hole.presentation.capture-title-enabled", true)) {
                player.sendTitle(
                        color(cfg().getString("storm.black-hole.presentation.capture-title", "&0&lEVENT HORIZON")),
                        color(cfg().getString(
                                "storm.black-hole.presentation.capture-subtitle",
                                "&5Пространство и время искажаются"
                        )),
                        3, 20, 7
                );
            }

            playSound("storm.black-hole.sounds.capture", Sound.ENTITY_ENDERMAN_TELEPORT, target.getLocation());
        }
    }

    private void updateTargets() {
        if (targets.isEmpty()) return;

        double pullStrength = d("storm.black-hole.gravity.pull-strength", 0.20, 0.0, 2.0);
        double damping = d("storm.black-hole.gravity.velocity-damping", 0.82, 0.0, 1.0);
        double coreMultiplier = d("storm.black-hole.gravity.core-multiplier", 1.8, 1.0, 8.0);

        double collapseBoost = collapsePullMultiplier();
        double pulseBoost = pulsePullMultiplier();

        int historyInterval = i(
                "storm.black-hole.time-fracture.history-interval-ticks", 2, 1, 20
        );
        int maxHistoryEntries = Math.max(
                4,
                secondsToTicks(d(
                        "storm.black-hole.time-fracture.max-history-seconds", 4.0, 1.0, 15.0
                )) / historyInterval
        );

        Iterator<Map.Entry<UUID, TargetState>> iterator = targets.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, TargetState> entry = iterator.next();
            Entity raw = Bukkit.getEntity(entry.getKey());
            if (!(raw instanceof LivingEntity target)
                    || !target.isValid()
                    || target.isDead()
                    || target.getWorld() != world) {
                storm.releaseTarget(entry.getKey());
                iterator.remove();
                continue;
            }

            Location location = target.getLocation();
            double distance = location.distance(center);
            if (distance > releaseRadius) {
                storm.releaseTarget(entry.getKey());
                iterator.remove();
                continue;
            }

            TargetState state = entry.getValue();
            if (ageTicks % historyInterval == 0) {
                state.record(location);
                state.trim(maxHistoryEntries);
            }

            applyBlindness(target, distance);
            renderTemporalEcho(target, state);
            applyGravity(
                    target,
                    distance,
                    pullStrength,
                    damping,
                    coreMultiplier,
                    collapseBoost,
                    pulseBoost
            );
        }
    }

    private void applyGravity(
            LivingEntity target,
            double distance,
            double pullStrength,
            double damping,
            double coreMultiplier,
            double collapseBoost,
            double pulseBoost
    ) {
        // IMPORTANT: player physics are deliberately NOT orbital.
        // The visual disk may rotate, but captured players are only sucked
        // horizontally toward the event horizon. No launch, no slam, no tornado.
        Vector horizontalToCenter = new Vector(
                center.getX() - target.getLocation().getX(),
                0.0,
                center.getZ() - target.getLocation().getZ()
        );
        double length = Math.max(0.001, horizontalToCenter.length());
        Vector inward = horizontalToCenter.multiply(1.0 / length);

        double horizontalDistance = Math.sqrt(
                Math.pow(center.getX() - target.getLocation().getX(), 2.0)
                        + Math.pow(center.getZ() - target.getLocation().getZ(), 2.0)
        );
        double depth = Math.max(0.0, Math.min(1.0, 1.0 - (horizontalDistance / radius)));
        double gravityCurve = 0.28 + depth * depth * 2.15;
        if (horizontalDistance <= coreRadius * 1.8) {
            gravityCurve *= coreMultiplier;
        }

        double strength = pullStrength * gravityCurve * collapseBoost * pulseBoost;
        Vector current = target.getVelocity();
        Vector next = new Vector(
                current.getX() * damping + inward.getX() * strength,
                current.getY(),
                current.getZ() * damping + inward.getZ() * strength
        );

        // Prevent this ability from becoming an accidental launcher.
        double maxUpward = d("storm.black-hole.gravity.max-upward-velocity", 0.22, 0.0, 1.5);
        if (next.getY() > maxUpward) {
            next.setY(maxUpward);
        }

        double maxHorizontal = d("storm.black-hole.gravity.max-horizontal-velocity", 1.45, 0.2, 5.0);
        double horizontalSpeed = Math.sqrt(next.getX() * next.getX() + next.getZ() * next.getZ());
        if (horizontalSpeed > maxHorizontal) {
            double scale = maxHorizontal / horizontalSpeed;
            next.setX(next.getX() * scale);
            next.setZ(next.getZ() * scale);
        }

        // Once the victim reaches the event horizon, absorb horizontal momentum
        // instead of letting them shoot through the center and bounce back.
        double captureRadius = d(
                "storm.black-hole.gravity.capture-radius",
                Math.max(1.0, coreRadius * 0.95),
                0.5,
                8.0
        );
        if (horizontalDistance <= captureRadius) {
            double captureDamping = d(
                    "storm.black-hole.gravity.capture-damping",
                    0.30,
                    0.0,
                    1.0
            );
            next.setX(next.getX() * captureDamping);
            next.setZ(next.getZ() * captureDamping);
        }

        target.setVelocity(next);
    }

    private void applyBlindness(LivingEntity target, double distance) {
        if (!(target instanceof Player player)) return;
        if (!cfg().getBoolean("storm.black-hole.blindness.enabled", true)) return;

        double blindnessRadius = d(
                "storm.black-hole.blindness.radius", radius, 0.5, 60.0
        );
        if (distance > blindnessRadius) return;

        int amplifier = i("storm.black-hole.blindness.amplifier", 0, 0, 4);
        int duration = i(
                "storm.black-hole.blindness.refresh-duration-ticks", 12, 6, 200
        );
        player.addPotionEffect(new PotionEffect(
                PotionEffectType.BLINDNESS,
                duration,
                amplifier,
                true,
                false,
                false
        ));
    }

    private void updateAmbientSound() {
        if (!cfg().getBoolean("storm.black-hole.sounds.ambient.enabled", true)) return;

        int interval = secondsToTicks(d(
                "storm.black-hole.sounds.ambient.interval-seconds", 2.5, 0.5, 20.0
        ));
        if (ageTicks > 0 && ageTicks % interval == 0) {
            playSound(
                    "storm.black-hole.sounds.ambient",
                    Sound.BLOCK_PORTAL_AMBIENT,
                    center
            );
        }
    }

    // ==========================================================
    // GRAVITY PULSE
    // ==========================================================

    private void updatePulse() {
        if (!cfg().getBoolean("storm.black-hole.gravity-pulse.enabled", true)) return;

        int interval = secondsToTicks(d(
                "storm.black-hole.gravity-pulse.interval-seconds", 4.0, 0.5, 30.0
        ));
        if (interval <= 0 || ageTicks == 0 || ageTicks % interval != 0) return;

        // Pure implosion pulse: no outward kick and no vertical launch.
        renderPulse();
        playSound(
                "storm.black-hole.sounds.gravity-pulse",
                Sound.BLOCK_SCULK_SHRIEKER_SHRIEK,
                center
        );
    }

    private double pulsePullMultiplier() {
        if (!cfg().getBoolean("storm.black-hole.gravity-pulse.enabled", true)) return 1.0;

        int interval = secondsToTicks(d(
                "storm.black-hole.gravity-pulse.interval-seconds", 4.0, 0.5, 30.0
        ));
        int crushTicks = i("storm.black-hole.gravity-pulse.crush-ticks", 18, 1, 80);
        if (interval <= 0 || ageTicks == 0) return 1.0;

        int phase = ageTicks % interval;
        if (phase <= crushTicks) {
            return d("storm.black-hole.gravity-pulse.crush-multiplier", 2.25, 1.0, 8.0);
        }
        return 1.0;
    }

    private void renderPulse() {
        if (!cfg().getBoolean("storm.black-hole.visuals.gravity-pulse-ring", true)) return;
        double density = visualDensity();
        for (int ring = 0; ring < 3; ring++) {
            double r = coreRadius + 1.3 + ring * 1.5;
            spawnRing(
                    center.clone().add(0, 0.3 + ring * 0.18, 0),
                    r,
                    scaled((int)Math.round((42 + ring * 12) * density)),
                    ageTicks * 0.22 + ring,
                    dustColor("storm.black-hole.visuals.colors.pulse", "#b75cff"),
                    1.35f
            );
        }
    }

    // ==========================================================
    // TIME FRACTURE + TEMPORAL ECHO
    // ==========================================================

    private void updateTimeFracture() {
        if (!cfg().getBoolean("storm.black-hole.time-fracture.enabled", true)) return;

        int interval = secondsToTicks(d(
                "storm.black-hole.time-fracture.interval-seconds", 6.0, 1.0, 40.0
        ));
        if (ageTicks == 0 || interval <= 0 || ageTicks % interval != 0) return;

        int historyInterval = i(
                "storm.black-hole.time-fracture.history-interval-ticks", 2, 1, 20
        );
        int rewindTicks = secondsToTicks(d(
                "storm.black-hole.time-fracture.rewind-seconds", 2.0, 0.25, 8.0
        ));
        int rewindEntries = Math.max(1, rewindTicks / historyInterval);

        for (TargetState state : targets.values()) {
            Entity raw = Bukkit.getEntity(state.id);
            if (!(raw instanceof LivingEntity target)
                    || target.getWorld() != world
                    || !target.isValid()
                    || target.isDead()) {
                continue;
            }

            Location past = state.locationFromEnd(rewindEntries);
            if (past == null || past.getWorld() != world) continue;

            Location current = target.getLocation().clone();
            renderFractureBurst(current);
            renderFractureBurst(past);

            // Rewind only horizontal space. Keeping current Y prevents the
            // Time Fracture from looking like the removed launch/slam ability.
            past.setY(current.getY());
            past.setYaw(current.getYaw());
            past.setPitch(current.getPitch());

            boolean teleported = target.teleport(past);
            if (teleported) {
                Vector velocity = target.getVelocity();
                target.setVelocity(new Vector(velocity.getX() * 0.25, velocity.getY(), velocity.getZ() * 0.25));
                playSound(
                        "storm.black-hole.sounds.time-fracture",
                        Sound.ENTITY_ENDERMAN_TELEPORT,
                        past
                );

                if (target instanceof Player player
                        && cfg().getBoolean(
                                "storm.black-hole.time-fracture.title-enabled", true
                        )) {
                    player.sendTitle(
                            color(cfg().getString(
                                    "storm.black-hole.time-fracture.title",
                                    "&5&lTIME FRACTURE"
                            )),
                            color(cfg().getString(
                                    "storm.black-hole.time-fracture.subtitle",
                                    "&7Тебя вернуло в прошлое"
                            )),
                            2, 12, 5
                    );
                }
            }
        }
    }

    private void renderTemporalEcho(LivingEntity target, TargetState state) {
        if (!cfg().getBoolean("storm.black-hole.temporal-echo.enabled", true)) return;

        int interval = i("storm.black-hole.temporal-echo.interval-ticks", 3, 1, 20);
        if (ageTicks % interval != 0) return;

        int points = i("storm.black-hole.temporal-echo.history-points", 6, 1, 20);
        int particles = i("storm.black-hole.temporal-echo.particles-per-point", 5, 1, 30);
        Color color = dustColor("storm.black-hole.visuals.colors.echo", "#8055ff");

        List<Location> echoes = state.echoLocations(points);
        for (int index = 0; index < echoes.size(); index++) {
            Location echo = echoes.get(index);
            float size = (float)(0.75 + index * 0.04);
            world.spawnParticle(
                    Particle.DUST,
                    echo.clone().add(0, 0.2, 0),
                    scaled(particles),
                    0.22, 0.20, 0.22,
                    0.0,
                    new Particle.DustOptions(color, size)
            );
            world.spawnParticle(
                    Particle.DUST,
                    echo.clone().add(0, 1.0, 0),
                    scaled(Math.max(1, particles - 1)),
                    0.18, 0.30, 0.18,
                    0.0,
                    new Particle.DustOptions(color, size)
            );
            world.spawnParticle(
                    Particle.DUST,
                    echo.clone().add(0, 1.75, 0),
                    scaled(Math.max(1, particles - 2)),
                    0.15, 0.16, 0.15,
                    0.0,
                    new Particle.DustOptions(color, size)
            );
        }
    }

    private void renderFractureBurst(Location at) {
        Color color = dustColor("storm.black-hole.visuals.colors.fracture", "#cf49ff");
        int count = scaled(i("storm.black-hole.visuals.fracture-burst-particles", 42, 4, 300));

        world.spawnParticle(
                Particle.DUST,
                at.clone().add(0, 1.0, 0),
                count,
                0.9, 1.15, 0.9,
                0.08,
                new Particle.DustOptions(color, 1.45f)
        );
        world.spawnParticle(
                Particle.REVERSE_PORTAL,
                at.clone().add(0, 1.0, 0),
                scaled(Math.max(8, count / 2)),
                0.8, 1.1, 0.8,
                0.16
        );
        world.spawnParticle(Particle.FLASH, at.clone().add(0, 1.1, 0), 1);
    }

    // ==========================================================
    // REALITY FRACTURES
    // ==========================================================

    private void renderRealityFractures() {
        if (!cfg().getBoolean("storm.black-hole.reality-fractures.enabled", true)) return;

        int count = i("storm.black-hole.reality-fractures.count", 4, 1, 12);
        int particles = scaled(i(
                "storm.black-hole.reality-fractures.particles-per-rift", 18, 2, 150
        ));
        Color color = dustColor("storm.black-hole.visuals.colors.fracture", "#cf49ff");

        for (int index = 0; index < count; index++) {
            Location rift = riftLocation(index, count);
            world.spawnParticle(
                    Particle.DUST,
                    rift,
                    particles,
                    0.28, 0.65, 0.28,
                    0.025,
                    new Particle.DustOptions(color, 1.25f)
            );
            world.spawnParticle(
                    Particle.REVERSE_PORTAL,
                    rift,
                    scaled(Math.max(3, particles / 3)),
                    0.30, 0.70, 0.30,
                    0.10
            );
        }
    }

    private Location riftLocation(int index, int count) {
        double orbit = d(
                "storm.black-hole.reality-fractures.orbit-radius", 6.5, 1.0, 25.0
        );
        double speed = d(
                "storm.black-hole.reality-fractures.rotation-speed", 0.035, 0.001, 0.3
        );

        double angle = ageTicks * speed + (Math.PI * 2.0 * index / Math.max(1, count));
        double wave = Math.sin(ageTicks * 0.045 + index * 1.7);
        double radial = orbit * (0.82 + 0.18 * Math.cos(ageTicks * 0.025 + index));
        return planePoint(radial, angle, wave * 0.35);
    }

    // ==========================================================
    // VISUALS
    // ==========================================================

    private void renderBlackHole() {
        double density = visualDensity();
        double remaining = Math.max(0.0, (durationTicks - ageTicks) / (double)durationTicks);
        double visualScale = formationVisualScale() * collapseVisualScale();

        renderCore(density, visualScale);
        renderLensingHalo(density, visualScale);
        renderAccretionDisk(density, visualScale);
        renderInfallStreams(density, visualScale);

        if (remaining < 0.12) {
            world.spawnParticle(
                    Particle.FLASH,
                    center,
                    (ageTicks % 5 == 0) ? 1 : 0
            );
        }
    }

    private void renderCore(double density, double collapseScale) {
        int points = scaled((int)Math.round(
                i("storm.black-hole.visuals.core-points", 180, 24, 900) * density
        ));
        Color coreColor = dustColor("storm.black-hole.visuals.colors.core", "#010103");
        Particle.DustOptions dust = new Particle.DustOptions(coreColor, 2.0f);

        double visualRadius = d(
                "storm.black-hole.visuals.event-horizon.radius",
                3.0,
                1.0,
                8.0
        ) * collapseScale;

        // Dense black disk in the same plane as the BlockDisplay event horizon.
        double golden = Math.PI * (3.0 - Math.sqrt(5.0));
        for (int index = 0; index < points; index++) {
            double t = Math.sqrt((index + 0.5) / points);
            double theta = index * golden + ageTicks * 0.008;
            double r = visualRadius * t;

            Location at = planePoint(r, theta, 0.0);
            world.spawnParticle(
                    Particle.DUST,
                    at,
                    1,
                    0.035, 0.035, 0.035,
                    0.0,
                    dust
            );

            if ((index + ageTicks) % 13 == 0) {
                world.spawnParticle(
                        Particle.LARGE_SMOKE,
                        at,
                        1,
                        0.05, 0.05, 0.05,
                        0.008
                );
            }
        }
    }

    private void renderLensingHalo(double density, double visualScale) {
        if (!cfg().getBoolean("storm.black-hole.visuals.lensing-halo.enabled", true)) return;

        double baseRadius = d(
                "storm.black-hole.visuals.event-horizon.radius",
                3.0,
                1.0,
                8.0
        ) * visualScale;
        double gap = d(
                "storm.black-hole.visuals.lensing-halo.gap",
                0.30,
                0.02,
                2.0
        );
        int layers = i("storm.black-hole.visuals.lensing-halo.layers", 3, 1, 8);
        int pointsPerLayer = i(
                "storm.black-hole.visuals.lensing-halo.points-per-layer",
                180,
                24,
                900
        );
        double wobble = d(
                "storm.black-hole.visuals.lensing-halo.wobble",
                0.10,
                0.0,
                0.8
        );

        Color inner = dustColor(
                "storm.black-hole.visuals.colors.halo-inner",
                "#eee8ff"
        );
        Color outer = dustColor(
                "storm.black-hole.visuals.colors.halo-outer",
                "#7e5cff"
        );

        for (int layer = 0; layer < layers; layer++) {
            double ratio = layers <= 1 ? 0.0 : layer / (double)(layers - 1);
            double ringRadius = baseRadius + gap + layer * gap * 0.55;
            int points = scaled((int)Math.round(
                    pointsPerLayer * density * (1.0 - ratio * 0.18)
            ));
            Color color = interpolate(inner, outer, ratio);
            Particle.DustOptions options = new Particle.DustOptions(
                    color,
                    (float)(1.55 - ratio * 0.30)
            );

            double phase = ageTicks * (0.010 + layer * 0.002);
            for (int index = 0; index < points; index++) {
                double angle = phase + Math.PI * 2.0 * index / points;
                double ripple = Math.sin(angle * 5.0 + ageTicks * 0.055 + layer)
                        * wobble * (1.0 - ratio * 0.30);
                Location at = planePoint(ringRadius + ripple, angle, 0.03 * layer);

                world.spawnParticle(
                        Particle.DUST,
                        at,
                        1,
                        0.012, 0.012, 0.012,
                        0.0,
                        options
                );

                if (layer == 0 && (index + ageTicks) % 18 == 0) {
                    world.spawnParticle(
                            Particle.END_ROD,
                            at,
                            1,
                            0.01, 0.01, 0.01,
                            0.01
                    );
                }
            }
        }
    }

    private void renderAccretionDisk(double density, double collapseScale) {
        int rings = i("storm.black-hole.visuals.accretion.rings", 5, 1, 12);
        int pointsPerRing = i("storm.black-hole.visuals.accretion.points-per-ring", 150, 16, 800);
        double innerRadius = d(
                "storm.black-hole.visuals.accretion.inner-radius",
                3.4,
                1.0,
                16.0
        ) * collapseScale;
        double outerRadius = d(
                "storm.black-hole.visuals.accretion.outer-radius",
                8.5,
                2.0,
                24.0
        ) * collapseScale;

        Color inner = dustColor("storm.black-hole.visuals.colors.disk-inner", "#6b35ff");
        Color outer = dustColor("storm.black-hole.visuals.colors.disk-outer", "#39d7ff");

        for (int ring = 0; ring < rings; ring++) {
            double ratio = rings <= 1 ? 0.5 : ring / (double)(rings - 1);
            double ringRadius = innerRadius + (outerRadius - innerRadius) * ratio;
            int points = scaled((int)Math.round(
                    pointsPerRing * density * (0.75 + ratio * 0.25)
            ));
            Color color = interpolate(inner, outer, ratio);
            Particle.DustOptions options = new Particle.DustOptions(
                    color,
                    (float)(1.40 - ratio * 0.30)
            );

            double phase = ageTicks * (0.018 + ring * 0.0025);
            for (int index = 0; index < points; index++) {
                double angle = phase + Math.PI * 2.0 * index / points;
                double depth = Math.sin(angle * 2.0 + ring) * (0.10 + ratio * 0.18);

                Location at = planePoint(ringRadius, angle, depth);
                world.spawnParticle(
                        Particle.DUST,
                        at,
                        1,
                        0.025, 0.025, 0.025,
                        0.0,
                        options
                );

                if ((index + ring + ageTicks) % 12 == 0) {
                    world.spawnParticle(
                            Particle.REVERSE_PORTAL,
                            at,
                            1,
                            0.02, 0.02, 0.02,
                            0.02
                    );
                }
            }
        }
    }

    private void renderInfallStreams(double density, double collapseScale) {
        if (!cfg().getBoolean("storm.black-hole.visuals.infall-streams.enabled", true)) return;

        int streams = i("storm.black-hole.visuals.infall-streams.count", 42, 4, 160);
        int trailPoints = i("storm.black-hole.visuals.infall-streams.trail-points", 4, 1, 12);
        double outerRadius = d(
                "storm.black-hole.visuals.infall-streams.outer-radius",
                Math.max(radius * 0.95, 10.0),
                3.0,
                40.0
        ) * collapseScale;
        double innerRadius = d(
                "storm.black-hole.visuals.infall-streams.inner-radius",
                3.2,
                0.75,
                12.0
        ) * collapseScale;
        double speed = d(
                "storm.black-hole.visuals.infall-streams.speed",
                0.020,
                0.002,
                0.20
        );

        Color outerColor = dustColor(
                "storm.black-hole.visuals.colors.infall-outer",
                "#47d9ff"
        );
        Color innerColor = dustColor(
                "storm.black-hole.visuals.colors.infall-inner",
                "#aa55ff"
        );
        double golden = Math.PI * (3.0 - Math.sqrt(5.0));

        for (int stream = 0; stream < streams; stream++) {
            double basePhase = (stream / (double)streams);
            double angle = stream * golden + ageTicks * 0.004;

            for (int trail = 0; trail < trailPoints; trail++) {
                double progress = (ageTicks * speed + basePhase - trail * 0.035) % 1.0;
                if (progress < 0.0) progress += 1.0;

                // This radius only decreases as progress advances:
                // the particle stream visually falls INTO the event horizon.
                double eased = progress * progress;
                double r = outerRadius - (outerRadius - innerRadius) * eased;

                double depth = Math.sin(stream * 1.73 + ageTicks * 0.045) * 0.75 * (1.0 - progress);
                double verticalWarp = Math.sin(stream * 0.91 + ageTicks * 0.018) * 0.55 * (1.0 - progress);

                Location at = planePoint(r, angle, depth).add(0.0, verticalWarp, 0.0);
                Color streamColor = interpolate(outerColor, innerColor, progress);
                Particle.DustOptions options = new Particle.DustOptions(
                        streamColor,
                        (float)(0.90 + progress * 0.45)
                );
                world.spawnParticle(
                        Particle.DUST,
                        at,
                        scaled(Math.max(1, (int)Math.round(density))),
                        0.06, 0.06, 0.06,
                        0.0,
                        options
                );

                if (trail == 0 || (stream + trail + ageTicks) % 3 == 0) {
                    world.spawnParticle(
                            Particle.REVERSE_PORTAL,
                            at,
                            scaled(Math.max(1, (int)Math.round(density))),
                            0.04, 0.04, 0.04,
                            0.03
                    );
                }
            }
        }
    }

    private Location planePoint(double radius, double angle, double depth) {
        Vector offset = planeRight.clone().multiply(Math.cos(angle) * radius)
                .add(planeUp.clone().multiply(Math.sin(angle) * radius))
                .add(planeNormal.clone().multiply(depth));
        return center.clone().add(offset);
    }

    private void spawnRing(
            Location at,
            double ringRadius,
            int points,
            double phase,
            Color color,
            float size
    ) {
        if (points <= 0 || ringRadius <= 0) return;
        Particle.DustOptions options = new Particle.DustOptions(color, size);
        for (int index = 0; index < points; index++) {
            double angle = phase + Math.PI * 2.0 * index / points;
            Vector offset = planeRight.clone().multiply(Math.cos(angle) * ringRadius)
                    .add(planeUp.clone().multiply(Math.sin(angle) * ringRadius));
            Location point = at.clone().add(offset);
            world.spawnParticle(Particle.DUST, point, 1, 0.02, 0.02, 0.02, 0.0, options);
        }
    }

    // ==========================================================
    // BLOCK DISPLAY SHARDS
    // ==========================================================

    private void spawnShards() {
        if (!cfg().getBoolean("storm.black-hole.visuals.event-horizon-blocks.enabled", true)) return;

        int rings = i("storm.black-hole.visuals.event-horizon-blocks.rings", 5, 1, 10);
        int basePoints = i("storm.black-hole.visuals.event-horizon-blocks.base-points", 10, 4, 48);
        double radius = d("storm.black-hole.visuals.event-horizon.radius", 3.0, 1.0, 8.0);
        double scale = d("storm.black-hole.visuals.event-horizon-blocks.scale", 0.42, 0.08, 1.5);

        List<Material> materials = shardMaterials();
        int materialIndex = 0;

        // Concentric rings fill a real black circular disk made of BlockDisplays.
        for (int ring = 1; ring <= rings; ring++) {
            double ratio = ring / (double)rings;
            double ringRadius = radius * ratio;
            int points = Math.max(6, (int)Math.round(basePoints + ratio * basePoints * 2.4));

            for (int index = 0; index < points; index++) {
                double angle = Math.PI * 2.0 * index / points;
                double formationStart = d(
                        "storm.black-hole.visuals.formation.block-start-radius-multiplier",
                        2.8,
                        1.0,
                        8.0
                );
                Location spawn = planePoint(ringRadius * formationStart, angle, 0.0);
                Material material = materials.get(materialIndex++ % materials.size());

                BlockDisplay display = world.spawn(spawn, BlockDisplay.class, entity -> {
                    entity.setBlock(material.createBlockData());
                    entity.setInterpolationDuration(2);
                    entity.setTransformation(new Transformation(
                            new Vector3f(
                                    (float)(-scale / 2.0),
                                    (float)(-scale / 2.0),
                                    (float)(-scale / 2.0)
                            ),
                            new AxisAngle4f(),
                            new Vector3f((float)scale, (float)scale, (float)scale),
                            new AxisAngle4f()
                    ));
                });

                shards.add(new Shard(
                        display,
                        angle,
                        ringRadius,
                        0.0,
                        d("storm.black-hole.visuals.event-horizon-blocks.rotation-speed", 0.006, 0.0, 0.05)
                                * (ring % 2 == 0 ? -1.0 : 1.0),
                        0.0,
                        0.0,
                        ring
                ));
            }
        }

        // Dense center blocks close the remaining hole.
        int centerBlocks = i(
                "storm.black-hole.visuals.event-horizon-blocks.center-blocks",
                7,
                1,
                24
        );
        double centerSpread = d(
                "storm.black-hole.visuals.event-horizon-blocks.center-spread",
                0.65,
                0.0,
                2.0
        );
        for (int index = 0; index < centerBlocks; index++) {
            double angle = Math.PI * 2.0 * index / centerBlocks;
            double r = index == 0 ? 0.0 : centerSpread;
            double centerStart = d(
                    "storm.black-hole.visuals.formation.center-start-radius",
                    5.5,
                    0.0,
                    20.0
            );
            Location spawn = planePoint(index == 0 ? centerStart : centerStart + r, angle, 0.02);
            Material material = materials.get(materialIndex++ % materials.size());

            BlockDisplay display = world.spawn(spawn, BlockDisplay.class, entity -> {
                entity.setBlock(material.createBlockData());
                entity.setInterpolationDuration(2);
                entity.setTransformation(new Transformation(
                        new Vector3f(
                                (float)(-scale / 2.0),
                                (float)(-scale / 2.0),
                                (float)(-scale / 2.0)
                        ),
                        new AxisAngle4f(),
                        new Vector3f((float)scale, (float)scale, (float)scale),
                        new AxisAngle4f()
                ));
            });

            shards.add(new Shard(display, angle, r, 0.0, 0.0, 0.0, 0.0, 0.0));
        }
    }

    private void updateShards() {
        if (shards.isEmpty()) return;

        double collapseScale = collapseVisualScale();
        double formation = formationProgress();
        double eased = easeOutCubic(formation);
        double startMultiplier = d(
                "storm.black-hole.visuals.formation.block-start-radius-multiplier",
                2.8,
                1.0,
                8.0
        );
        double centerStart = d(
                "storm.black-hole.visuals.formation.center-start-radius",
                5.5,
                0.0,
                20.0
        );
        double formationDepth = d(
                "storm.black-hole.visuals.formation.depth",
                2.4,
                0.0,
                10.0
        );

        for (Shard shard : shards) {
            if (!shard.display.isValid()) continue;

            double angle = shard.angle + ageTicks * shard.speed;
            double finalRadius = shard.orbit * collapseScale;

            double currentRadius;
            if (shard.orbit <= 0.001) {
                currentRadius = centerStart * (1.0 - eased) * collapseScale;
            } else {
                double multiplier = startMultiplier + (1.0 - startMultiplier) * eased;
                currentRadius = finalRadius * multiplier;
            }

            double alternatingDepth = ((int)Math.round(shard.phase) % 2 == 0 ? 1.0 : -1.0)
                    * formationDepth * (1.0 - eased);
            Location next = planePoint(currentRadius, angle, alternatingDepth);
            shard.display.teleport(next);
        }
    }

    private List<Material> shardMaterials() {
        List<String> configured = cfg().getStringList(
                "storm.black-hole.visuals.event-horizon-blocks.materials"
        );
        List<Material> result = new ArrayList<>();

        for (String raw : configured) {
            Material material = Material.matchMaterial(raw);
            if (material != null && material.isBlock()) result.add(material);
        }

        if (result.isEmpty()) {
            result.add(Material.BLACK_CONCRETE);
            result.add(Material.COAL_BLOCK);
            result.add(Material.OBSIDIAN);
            result.add(Material.BLACKSTONE);
        }
        return result;
    }

    private void updateCollapseCountdown() {
        if (!cfg().getBoolean("storm.black-hole.collapse.enabled", true)
                || !cfg().getBoolean("storm.black-hole.collapse.countdown-title-enabled", true)) {
            return;
        }

        int warningTicks = secondsToTicks(d(
                "storm.black-hole.collapse.warning-seconds", 3.0, 0.25, 15.0
        ));
        int remainingTicks = durationTicks - ageTicks;
        if (remainingTicks <= 0 || remainingTicks > warningTicks) return;

        int remainingSeconds = Math.max(1, (int)Math.ceil(remainingTicks / 20.0));
        if (remainingTicks % 20 != 0 && remainingTicks != warningTicks) return;

        String title = color(cfg().getString(
                "storm.black-hole.collapse.countdown-title",
                "&5&lСХЛОПЫВАНИЕ"
        ));
        String subtitle = color(cfg().getString(
                "storm.black-hole.collapse.countdown-subtitle",
                "&f%time%"
        ).replace("%time%", String.valueOf(remainingSeconds)));

        Set<UUID> viewers = new LinkedHashSet<>(targets.keySet());
        if (cfg().getBoolean("storm.black-hole.collapse.countdown-owner", true)) {
            viewers.add(ownerId);
        }

        for (UUID viewerId : viewers) {
            Player viewer = Bukkit.getPlayer(viewerId);
            if (viewer == null || !viewer.isOnline() || viewer.getWorld() != world) continue;
            viewer.sendTitle(title, subtitle, 0, 16, 4);
        }

        if (cfg().getBoolean("storm.black-hole.collapse.countdown-sound-enabled", true)) {
            playSound(
                    "storm.black-hole.sounds.collapse-countdown",
                    Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE,
                    center
            );
        }
    }

    // ==========================================================
    // COLLAPSE + CLEANUP
    // ==========================================================

    private double formationProgress() {
        int formationTicks = i(
                "storm.black-hole.visuals.formation.duration-ticks",
                28,
                1,
                100
        );
        return Math.max(0.0, Math.min(1.0, ageTicks / (double)formationTicks));
    }

    private double formationVisualScale() {
        double startScale = d(
                "storm.black-hole.visuals.formation.start-scale",
                0.16,
                0.02,
                1.0
        );
        double eased = easeOutCubic(formationProgress());
        return startScale + (1.0 - startScale) * eased;
    }

    private double easeOutCubic(double t) {
        t = Math.max(0.0, Math.min(1.0, t));
        double inv = 1.0 - t;
        return 1.0 - inv * inv * inv;
    }

    private double collapsePullMultiplier() {
        if (!cfg().getBoolean("storm.black-hole.collapse.enabled", true)) return 1.0;

        int warningTicks = secondsToTicks(d(
                "storm.black-hole.collapse.warning-seconds", 3.0, 0.25, 15.0
        ));
        int remaining = durationTicks - ageTicks;
        if (remaining > warningTicks) return 1.0;

        double progress = 1.0 - (remaining / (double)Math.max(1, warningTicks));
        double max = d("storm.black-hole.collapse.final-pull-multiplier", 3.2, 1.0, 10.0);
        return 1.0 + progress * (max - 1.0);
    }

    private double collapseVisualScale() {
        if (!cfg().getBoolean("storm.black-hole.collapse.enabled", true)) return 1.0;

        int warningTicks = secondsToTicks(d(
                "storm.black-hole.collapse.warning-seconds", 3.0, 0.25, 15.0
        ));
        int remaining = durationTicks - ageTicks;
        if (remaining > warningTicks) return 1.0;

        double progress = 1.0 - (remaining / (double)Math.max(1, warningTicks));
        return Math.max(0.16, 1.0 - progress * 0.84);
    }

    private void finish(boolean collapse) {
        if (finished) return;
        finished = true;

        if (collapse) {
            renderCollapse();
            settleTargets();
        }

        for (Shard shard : shards) {
            if (shard.display != null && shard.display.isValid()) shard.display.remove();
        }
        shards.clear();

        for (UUID targetId : new ArrayList<>(targets.keySet())) {
            storm.releaseTarget(targetId);
        }
        targets.clear();

        storm.onBlackHoleFinished(this);
    }

    private void renderCollapse() {
        double density = visualDensity();
        Color collapseColor = dustColor(
                "storm.black-hole.visuals.colors.collapse",
                "#f2e8ff"
        );

        world.spawnParticle(Particle.FLASH, center, 3);
        world.spawnParticle(
                Particle.REVERSE_PORTAL,
                center,
                scaled((int)Math.round(320 * density)),
                1.55, 1.55, 1.55,
                0.65
        );
        world.spawnParticle(
                Particle.DUST,
                center,
                scaled((int)Math.round(220 * density)),
                1.15, 1.15, 1.15,
                0.12,
                new Particle.DustOptions(collapseColor, 1.85f)
        );

        // Final lensing snap. It is purely visual; no physical knockback.
        for (int ring = 0; ring < 7; ring++) {
            spawnRing(
                    center,
                    1.1 + ring * 1.15,
                    scaled((int)Math.round((72 + ring * 16) * density)),
                    ring * 0.27,
                    interpolate(
                            collapseColor,
                            dustColor("storm.black-hole.visuals.colors.halo-outer", "#7e5cff"),
                            ring / 6.0
                    ),
                    (float)(1.75 - ring * 0.10)
            );
        }

        if (cfg().getBoolean("storm.black-hole.collapse.sonic-boom-effect", true)) {
            world.spawnParticle(Particle.SONIC_BOOM, center, 1);
        }
        if (cfg().getBoolean("storm.black-hole.collapse.lightning-effect", true)) {
            world.strikeLightningEffect(center);
        }
        playSound("storm.black-hole.sounds.collapse", Sound.ENTITY_GENERIC_EXPLODE, center);
    }

    private void settleTargets() {
        boolean damageEnabled = cfg().getBoolean(
                "storm.black-hole.collapse.damage-enabled",
                false
        );
        double damage = d(
                "storm.black-hole.collapse.damage-hearts",
                0.0,
                0.0,
                20.0
        ) * 2.0;

        Player owner = Bukkit.getPlayer(ownerId);

        for (UUID id : targets.keySet()) {
            Entity raw = Bukkit.getEntity(id);
            if (!(raw instanceof LivingEntity target)
                    || !target.isValid()
                    || target.isDead()
                    || target.getWorld() != world) {
                continue;
            }

            // End the ability without throwing anyone upward or down.
            Vector velocity = target.getVelocity();
            target.setVelocity(new Vector(
                    velocity.getX() * 0.20,
                    Math.min(velocity.getY(), 0.10),
                    velocity.getZ() * 0.20
            ));

            if (damageEnabled && damage > 0.0) {
                if (owner != null && owner.isOnline()) target.damage(damage, owner);
                else target.damage(damage);
            }
        }
    }

    // ==========================================================
    // HELPERS
    // ==========================================================

    private FileConfiguration cfg() {
        return host.getConfig();
    }

    private int i(String path, int fallback, int min, int max) {
        return Math.max(min, Math.min(max, cfg().getInt(path, fallback)));
    }

    private double d(String path, double fallback, double min, double max) {
        double value = cfg().getDouble(path, fallback);
        if (!Double.isFinite(value)) value = fallback;
        return Math.max(min, Math.min(max, value));
    }

    private int secondsToTicks(double seconds) {
        return Math.max(1, (int)Math.round(seconds * 20.0));
    }

    private double visualDensity() {
        return d("storm.black-hole.visuals.particle-density", 1.8, 0.1, 5.0);
    }

    private int scaled(int base) {
        int safe = Math.max(0, base);
        if (!cfg().getBoolean("storm.black-hole.visuals.respect-serverguardian", false)) {
            return safe;
        }
        return host.scaleCosmeticCount(safe);
    }

    private Color dustColor(String path, String fallback) {
        String raw = cfg().getString(path, fallback);
        if (raw == null) raw = fallback;
        String clean = raw.trim().replace("#", "");

        try {
            if (clean.length() == 6) {
                return Color.fromRGB(Integer.parseInt(clean, 16));
            }
        } catch (IllegalArgumentException ignored) {
        }
        return Color.fromRGB(128, 85, 255);
    }

    private Color interpolate(Color a, Color b, double t) {
        t = Math.max(0.0, Math.min(1.0, t));
        int red = (int)Math.round(a.getRed() + (b.getRed() - a.getRed()) * t);
        int green = (int)Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t);
        int blue = (int)Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t);
        return Color.fromRGB(red, green, blue);
    }

    private void playSound(String path, Sound fallback, Location at) {
        String raw = cfg().getString(path + ".sound", fallback.name());
        Sound sound = fallback;
        if (raw != null) {
            try {
                sound = Sound.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                host.getLogger().warning("Unknown STORM sound at " + path + ": " + raw);
            }
        }

        float volume = (float)d(path + ".volume", 1.0, 0.0, 5.0);
        float pitch = (float)d(path + ".pitch", 1.0, 0.01, 2.0);
        world.playSound(at, sound, volume, pitch);
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    private record Shard(
            BlockDisplay display,
            double angle,
            double orbit,
            double baseHeight,
            double speed,
            double bobSpeed,
            double bobAmount,
            double phase
    ) {}

    private static final class TargetState {
        private final UUID id;
        private final ArrayDeque<Location> history = new ArrayDeque<>();

        private TargetState(UUID id) {
            this.id = id;
        }

        private void record(Location location) {
            history.addLast(location.clone());
        }

        private void trim(int maxEntries) {
            while (history.size() > maxEntries) history.removeFirst();
        }

        private Location locationFromEnd(int entriesBack) {
            if (history.isEmpty()) return null;
            List<Location> copy = new ArrayList<>(history);
            int index = Math.max(0, copy.size() - 1 - entriesBack);
            return copy.get(index).clone();
        }

        private List<Location> echoLocations(int requested) {
            if (history.isEmpty() || requested <= 0) return List.of();

            List<Location> copy = new ArrayList<>(history);
            List<Location> result = new ArrayList<>();
            int amount = Math.min(requested, copy.size());
            for (int n = 0; n < amount; n++) {
                double ratio = amount <= 1 ? 1.0 : n / (double)(amount - 1);
                int index = (int)Math.round(ratio * (copy.size() - 1));
                result.add(copy.get(index).clone());
            }
            return result;
        }
    }
}
