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

        updatePulse();
        updateTimeFracture();
        updateTargets();

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
        double orbitStrength = d("storm.black-hole.gravity.orbit-strength", 0.15, 0.0, 2.0);
        double verticalStrength = d("storm.black-hole.gravity.vertical-strength", 0.12, 0.0, 1.0);
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
            applyGravity(target, distance, pullStrength, orbitStrength, verticalStrength,
                    damping, coreMultiplier, collapseBoost, pulseBoost);
            applyRealityFractureGravity(target);

            target.setFallDistance(0f);
        }
    }

    private void applyGravity(
            LivingEntity target,
            double distance,
            double pullStrength,
            double orbitStrength,
            double verticalStrength,
            double damping,
            double coreMultiplier,
            double collapseBoost,
            double pulseBoost
    ) {
        Vector toCenter = center.toVector().subtract(target.getLocation().toVector());
        double length = Math.max(0.001, toCenter.length());
        Vector inward = toCenter.clone().multiply(1.0 / length);

        // Stronger toward the event horizon, but still escapable near the outer radius.
        double depth = Math.max(0.0, Math.min(1.0, 1.0 - (distance / radius)));
        double gravityCurve = 0.35 + depth * depth * 1.85;
        if (distance <= coreRadius * 1.8) gravityCurve *= coreMultiplier;

        Vector horizontal = new Vector(inward.getX(), 0.0, inward.getZ());
        Vector tangent;
        if (horizontal.lengthSquared() > 0.0001) {
            horizontal.normalize();
            tangent = new Vector(-horizontal.getZ(), 0.0, horizontal.getX());
        } else {
            tangent = new Vector(0.0, 0.0, 0.0);
        }

        double direction = ((sessionId.hashCode() & 1) == 0) ? 1.0 : -1.0;

        Vector acceleration = inward.multiply(
                pullStrength * gravityCurve * collapseBoost * pulseBoost
        );
        acceleration.add(tangent.multiply(
                orbitStrength * (0.25 + depth) * direction
        ));

        double yDifference = center.getY() - target.getLocation().getY();
        acceleration.setY(
                acceleration.getY() + Math.max(-0.30, Math.min(0.30, yDifference * verticalStrength))
        );

        Vector velocity = target.getVelocity().multiply(damping).add(acceleration);
        double maxVelocity = d("storm.black-hole.gravity.max-velocity", 2.7, 0.3, 8.0);
        if (velocity.length() > maxVelocity) {
            velocity.normalize().multiply(maxVelocity);
        }
        target.setVelocity(velocity);
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

    // ==========================================================
    // GRAVITY PULSE
    // ==========================================================

    private void updatePulse() {
        if (!cfg().getBoolean("storm.black-hole.gravity-pulse.enabled", true)) return;

        int interval = secondsToTicks(d(
                "storm.black-hole.gravity-pulse.interval-seconds", 4.0, 0.5, 30.0
        ));
        if (interval <= 0 || ageTicks == 0 || ageTicks % interval != 0) return;

        double kick = d("storm.black-hole.gravity-pulse.outward-kick", 0.55, 0.0, 3.0);
        for (UUID id : targets.keySet()) {
            Entity raw = Bukkit.getEntity(id);
            if (!(raw instanceof LivingEntity target) || target.getWorld() != world) continue;

            Vector outward = target.getLocation().toVector().subtract(center.toVector());
            if (outward.lengthSquared() > 0.001) {
                outward.normalize().multiply(kick);
                outward.setY(Math.max(0.08, outward.getY() + 0.12));
                target.setVelocity(target.getVelocity().multiply(0.45).add(outward));
            }
        }

        renderPulse();
        playSound("storm.black-hole.sounds.gravity-pulse", Sound.ENTITY_LIGHTNING_BOLT_THUNDER, center);
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

            boolean teleported = target.teleport(past);
            if (teleported) {
                target.setVelocity(new Vector(0.0, 0.06, 0.0));
                target.setFallDistance(0f);
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

    private void applyRealityFractureGravity(LivingEntity target) {
        if (!cfg().getBoolean("storm.black-hole.reality-fractures.enabled", true)) return;

        int count = i("storm.black-hole.reality-fractures.count", 4, 1, 12);
        double influence = d(
                "storm.black-hole.reality-fractures.influence-radius", 3.2, 0.5, 10.0
        );
        double strength = d(
                "storm.black-hole.reality-fractures.pull-strength", 0.20, 0.0, 2.0
        );

        for (int index = 0; index < count; index++) {
            Location rift = riftLocation(index, count);
            double distance = rift.distance(target.getLocation());
            if (distance > influence || distance < 0.001) continue;

            Vector pull = rift.toVector().subtract(target.getLocation().toVector());
            if (pull.lengthSquared() <= 0.001) continue;

            double scale = (1.0 - distance / influence) * strength;
            target.setVelocity(target.getVelocity().add(pull.normalize().multiply(scale)));
        }
    }

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
        return center.clone().add(
                Math.cos(angle) * radial,
                wave * 2.25,
                Math.sin(angle) * radial
        );
    }

    // ==========================================================
    // VISUALS
    // ==========================================================

    private void renderBlackHole() {
        double density = visualDensity();
        double remaining = Math.max(0.0, (durationTicks - ageTicks) / (double)durationTicks);
        double collapseScale = collapseVisualScale();

        renderCore(density, collapseScale);
        renderAccretionDisk(density, collapseScale);
        renderSpiralArms(density, collapseScale);

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
                i("storm.black-hole.visuals.core-points", 90, 12, 500) * density
        ));
        Color coreColor = dustColor("storm.black-hole.visuals.colors.core", "#050509");
        Particle.DustOptions dust = new Particle.DustOptions(coreColor, 1.65f);

        double r = coreRadius * collapseScale;
        double golden = Math.PI * (3.0 - Math.sqrt(5.0));
        for (int index = 0; index < points; index++) {
            double y = 1.0 - 2.0 * ((index + 0.5) / points);
            double radial = Math.sqrt(Math.max(0.0, 1.0 - y * y));
            double theta = golden * index + ageTicks * 0.075;

            Location at = center.clone().add(
                    Math.cos(theta) * radial * r,
                    y * r,
                    Math.sin(theta) * radial * r
            );
            world.spawnParticle(Particle.DUST, at, 1, 0.03, 0.03, 0.03, 0.0, dust);

            if ((index + ageTicks) % 8 == 0) {
                world.spawnParticle(Particle.LARGE_SMOKE, at, 1, 0.04, 0.04, 0.04, 0.01);
            }
        }

        world.spawnParticle(
                Particle.REVERSE_PORTAL,
                center,
                scaled((int)Math.round(24 * density)),
                r * 0.55, r * 0.55, r * 0.55,
                0.18
        );
    }

    private void renderAccretionDisk(double density, double collapseScale) {
        int rings = i("storm.black-hole.visuals.disk-rings", 4, 1, 10);
        int basePoints = i("storm.black-hole.visuals.disk-points", 110, 16, 600);
        Color inner = dustColor("storm.black-hole.visuals.colors.disk-inner", "#7046ff");
        Color outer = dustColor("storm.black-hole.visuals.colors.disk-outer", "#34c8ff");

        for (int ring = 0; ring < rings; ring++) {
            double ratio = rings <= 1 ? 0.5 : ring / (double)(rings - 1);
            double ringRadius = (
                    coreRadius * 1.45 + ratio * (radius * 0.72 - coreRadius * 1.45)
            ) * collapseScale;
            int points = scaled((int)Math.round(basePoints * density * (0.70 + ratio * 0.30)));
            double phase = ageTicks * (0.055 + ring * 0.012) * (ring % 2 == 0 ? 1.0 : -1.0);
            Color color = interpolate(inner, outer, ratio);
            Particle.DustOptions options = new Particle.DustOptions(
                    color,
                    (float)(1.35 - ratio * 0.35)
            );

            for (int index = 0; index < points; index++) {
                double angle = phase + Math.PI * 2.0 * index / points;
                double wobble = Math.sin(angle * 3.0 + ageTicks * 0.08 + ring) * (0.12 + ratio * 0.22);
                Location at = center.clone().add(
                        Math.cos(angle) * ringRadius,
                        wobble + Math.sin(angle + ring) * ringRadius * 0.06,
                        Math.sin(angle) * ringRadius
                );

                world.spawnParticle(Particle.DUST, at, 1, 0.035, 0.025, 0.035, 0.0, options);
                if ((index + ring + ageTicks) % 11 == 0) {
                    world.spawnParticle(Particle.ELECTRIC_SPARK, at, 1, 0.03, 0.03, 0.03, 0.025);
                }
            }
        }
    }

    private void renderSpiralArms(double density, double collapseScale) {
        if (!cfg().getBoolean("storm.black-hole.visuals.spiral-arms.enabled", true)) return;

        int arms = i("storm.black-hole.visuals.spiral-arms.count", 6, 1, 16);
        int pointsPerArm = scaled((int)Math.round(
                i("storm.black-hole.visuals.spiral-arms.points-per-arm", 28, 4, 160) * density
        ));
        Color color = dustColor("storm.black-hole.visuals.colors.spiral", "#8a5cff");
        Particle.DustOptions options = new Particle.DustOptions(color, 1.0f);

        for (int arm = 0; arm < arms; arm++) {
            double armOffset = Math.PI * 2.0 * arm / arms;
            for (int point = 0; point < pointsPerArm; point++) {
                double t = point / (double)Math.max(1, pointsPerArm - 1);
                double r = (coreRadius * 1.25 + (radius * 0.90 - coreRadius * 1.25) * t)
                        * collapseScale;
                double angle = armOffset + ageTicks * 0.065 + t * Math.PI * 3.4;
                double y = Math.sin(t * Math.PI * 2.0 + arm) * (0.25 + t * 0.65);

                Location at = center.clone().add(
                        Math.cos(angle) * r,
                        y,
                        Math.sin(angle) * r
                );
                world.spawnParticle(Particle.DUST, at, 1, 0.02, 0.02, 0.02, 0.0, options);
                if ((point + arm + ageTicks) % 7 == 0) {
                    world.spawnParticle(Particle.REVERSE_PORTAL, at, 1, 0.02, 0.04, 0.02, 0.035);
                }
            }
        }
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
            Location point = at.clone().add(
                    Math.cos(angle) * ringRadius,
                    0,
                    Math.sin(angle) * ringRadius
            );
            world.spawnParticle(Particle.DUST, point, 1, 0.02, 0.02, 0.02, 0.0, options);
        }
    }

    // ==========================================================
    // BLOCK DISPLAY SHARDS
    // ==========================================================

    private void spawnShards() {
        if (!cfg().getBoolean("storm.black-hole.visuals.block-shards.enabled", true)) return;

        int count = i("storm.black-hole.visuals.block-shards.count", 22, 0, 80);
        double minOrbit = d("storm.black-hole.visuals.block-shards.orbit-min", 2.8, 0.5, 20.0);
        double maxOrbit = d("storm.black-hole.visuals.block-shards.orbit-max", 9.5, minOrbit, 35.0);
        double scale = d("storm.black-hole.visuals.block-shards.scale", 0.28, 0.05, 2.0);

        List<Material> materials = shardMaterials();
        ThreadLocalRandom random = ThreadLocalRandom.current();

        for (int index = 0; index < count; index++) {
            double orbit = random.nextDouble(minOrbit, Math.max(minOrbit + 0.01, maxOrbit));
            double height = random.nextDouble(-2.8, 2.8);
            double angle = random.nextDouble(0.0, Math.PI * 2.0);
            double speed = random.nextDouble(0.018, 0.055) * (random.nextBoolean() ? 1.0 : -1.0);
            double bobSpeed = random.nextDouble(0.025, 0.075);
            double bobAmount = random.nextDouble(0.25, 1.35);

            Location spawn = center.clone().add(
                    Math.cos(angle) * orbit,
                    height,
                    Math.sin(angle) * orbit
            );

            Material material = materials.get(index % materials.size());
            BlockDisplay display = world.spawn(spawn, BlockDisplay.class, entity -> {
                entity.setBlock(material.createBlockData());
                entity.setInterpolationDuration(2);
                entity.setTransformation(new Transformation(
                        new Vector3f((float)(-scale / 2.0), (float)(-scale / 2.0), (float)(-scale / 2.0)),
                        new AxisAngle4f(),
                        new Vector3f((float)scale, (float)scale, (float)scale),
                        new AxisAngle4f()
                ));
            });

            shards.add(new Shard(
                    display,
                    angle,
                    orbit,
                    height,
                    speed,
                    bobSpeed,
                    bobAmount,
                    random.nextDouble(0.0, Math.PI * 2.0)
            ));
        }
    }

    private void updateShards() {
        if (shards.isEmpty()) return;

        double collapseScale = collapseVisualScale();
        for (Shard shard : shards) {
            if (!shard.display.isValid()) continue;

            double angle = shard.angle + ageTicks * shard.speed;
            double orbit = shard.orbit * collapseScale;
            double y = shard.baseHeight
                    + Math.sin(shard.phase + ageTicks * shard.bobSpeed) * shard.bobAmount;

            Location next = center.clone().add(
                    Math.cos(angle) * orbit,
                    y * collapseScale,
                    Math.sin(angle) * orbit
            );
            next.setYaw((float)Math.toDegrees(-angle));
            next.setPitch((float)(Math.sin(angle * 0.7) * 25.0));
            shard.display.teleport(next);
        }
    }

    private List<Material> shardMaterials() {
        List<String> configured = cfg().getStringList(
                "storm.black-hole.visuals.block-shards.materials"
        );
        List<Material> result = new ArrayList<>();

        for (String raw : configured) {
            Material material = Material.matchMaterial(raw);
            if (material != null && material.isBlock()) result.add(material);
        }

        if (result.isEmpty()) {
            result.add(Material.BLACK_CONCRETE);
            result.add(Material.OBSIDIAN);
            result.add(Material.CRYING_OBSIDIAN);
            result.add(Material.TINTED_GLASS);
        }
        return result;
    }

    // ==========================================================
    // COLLAPSE + CLEANUP
    // ==========================================================

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
            ejectTargets();
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
                "storm.black-hole.visuals.colors.collapse", "#f2e8ff"
        );

        world.spawnParticle(Particle.FLASH, center, 4);
        world.spawnParticle(
                Particle.REVERSE_PORTAL,
                center,
                scaled((int)Math.round(260 * density)),
                coreRadius * 2.2, coreRadius * 2.2, coreRadius * 2.2,
                0.55
        );
        world.spawnParticle(
                Particle.DUST,
                center,
                scaled((int)Math.round(180 * density)),
                2.5, 2.5, 2.5,
                0.18,
                new Particle.DustOptions(collapseColor, 1.75f)
        );
        world.spawnParticle(
                Particle.CLOUD,
                center,
                scaled((int)Math.round(150 * density)),
                2.5, 1.0, 2.5,
                0.25
        );

        for (int ring = 0; ring < 5; ring++) {
            spawnRing(
                    center.clone().add(0, ring * 0.15, 0),
                    2.0 + ring * 2.3,
                    scaled((int)Math.round((60 + ring * 18) * density)),
                    ring * 0.35,
                    collapseColor,
                    1.65f
            );
        }

        if (cfg().getBoolean("storm.black-hole.collapse.lightning-effect", true)) {
            world.strikeLightningEffect(center);
        }
        playSound("storm.black-hole.sounds.collapse", Sound.ENTITY_GENERIC_EXPLODE, center);
    }

    private void ejectTargets() {
        double power = d("storm.black-hole.collapse.eject-power", 1.65, 0.0, 6.0);
        double vertical = d("storm.black-hole.collapse.eject-y", 0.65, -1.0, 3.0);
        boolean damageEnabled = cfg().getBoolean(
                "storm.black-hole.collapse.damage-enabled", true
        );
        double damage = d(
                "storm.black-hole.collapse.damage-hearts", 2.0, 0.0, 20.0
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

            Vector outward = target.getLocation().toVector().subtract(center.toVector());
            if (outward.lengthSquared() < 0.001) {
                double randomAngle = ThreadLocalRandom.current().nextDouble(0.0, Math.PI * 2.0);
                outward = new Vector(Math.cos(randomAngle), 0.0, Math.sin(randomAngle));
            } else {
                outward.normalize();
            }

            outward.multiply(power);
            outward.setY(vertical);
            target.setVelocity(outward);
            target.setFallDistance(0f);

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
