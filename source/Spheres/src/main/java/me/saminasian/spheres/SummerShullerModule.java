package me.saminasian.spheres;

import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import java.math.BigDecimal;
import java.util.*;

public final class SummerShullerModule extends SphereModule implements Listener {
    public SummerShullerModule(SpheresPlugin host) { super(host); }

    private static final UUID SUMMER_DAMAGE_UUID =
            UUID.fromString("70449cb7-22ed-45b6-a948-927bf1660001");
    private static final UUID SUMMER_ARMOR_UUID =
            UUID.fromString("70449cb7-22ed-45b6-a948-927bf1660002");
    private static final UUID SHULLER_DAMAGE_UUID =
            UUID.fromString("70449cb7-22ed-45b6-a948-927bf1660003");
    private static final UUID SHULLER_SPEED_UUID =
            UUID.fromString("70449cb7-22ed-45b6-a948-927bf1660004");

    private NamespacedKey summerKey;
    private NamespacedKey shullerKey;

    private final Map<UUID, Long> summerCooldowns = new HashMap<UUID, Long>();
    private final Map<UUID, Long> shullerCooldowns = new HashMap<UUID, Long>();

    private final Map<UUID, ActiveMelon> melons = new HashMap<UUID, ActiveMelon>();
    private final Map<UUID, ActiveSlice> slices = new HashMap<UUID, ActiveSlice>();

    private final Map<UUID, Long> hiddenShullerUntil = new HashMap<UUID, Long>();
    private final Map<UUID, PendingStrength> pendingShullerStrength = new HashMap<UUID, PendingStrength>();
    private final Set<Entity> shullerVisualEntities = new HashSet<Entity>();

    private final Random random = new Random();
    private final Map<UUID, HeatHit> heatHits = new HashMap<>();
    private static final class HeatHit {
        final LivingEntity owner; boolean accepted;
        HeatHit(LivingEntity owner) { this.owner = owner; }
    }

    private BukkitTask melonTask;
    private BukkitTask sliceTask;

    @Override
    public void start() {

        summerKey = new NamespacedKey(host, "summer_ball");
        shullerKey = new NamespacedKey(host, "shuller_ball");

        Bukkit.getPluginManager().registerEvents(this, host);

        getLogger().info("SummerBall SUMMER + SHULLER enabled for Leaf/Paper 1.21.11");
    }

    @Override
    public void stop() {
        if (melonTask != null) {
            melonTask.cancel();
            melonTask = null;
        }
        if (sliceTask != null) {
            sliceTask.cancel();
            sliceTask = null;
        }

        for (ActiveMelon active : new ArrayList<ActiveMelon>(melons.values())) {
            if (active.entity != null) {
                active.entity.remove();
            }
        }
        for (ActiveSlice active : new ArrayList<ActiveSlice>(slices.values())) {
            if (active.entity != null) {
                active.entity.remove();
            }
        }
        for (Entity entity : new ArrayList<Entity>(shullerVisualEntities)) {
            if (entity != null) {
                entity.remove();
            }
        }

        for (UUID hiddenId : new ArrayList<UUID>(hiddenShullerUntil.keySet())) {
            Player hidden = Bukkit.getPlayer(hiddenId);
            if (hidden == null) {
                continue;
            }
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                if (!viewer.getUniqueId().equals(hiddenId)) {
                    viewer.showPlayer(host, hidden);
                }
            }
        }

        for (UUID playerId : new ArrayList<UUID>(pendingShullerStrength.keySet())) {
            clearShullerStrength(playerId, true);
        }

        melons.clear();
        slices.clear();
        shullerVisualEntities.clear();
        hiddenShullerUntil.clear();
        pendingShullerStrength.clear();
        summerCooldowns.clear();
        shullerCooldowns.clear();
    }

    public ItemStack createSummerBall() {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD, 1);
        ItemMeta rawMeta = item.getItemMeta();
        if (!(rawMeta instanceof SkullMeta)) {
            return item;
        }

        SkullMeta meta = (SkullMeta) rawMeta;
        double damage = d("summer.item.attributes.damage", 4.0, -100.0, 100.0);
        double armor = d("summer.item.attributes.armor", 7.35, -100.0, 100.0);

        meta.setDisplayName(formatSummerItem(getConfig().getString("summer.item.name", "&a&lШАР &f &aSUMMER"), damage, armor));

        List<String> lore = new ArrayList<String>();
        for (String line : getConfig().getStringList("summer.item.lore")) {
            lore.add(formatSummerItem(line, damage, armor));
        }
        meta.setLore(lore);

        meta.getPersistentDataContainer().set(summerKey, PersistentDataType.BYTE, (byte) 1);

        meta.addAttributeModifier(
                Attribute.ATTACK_DAMAGE,
                new AttributeModifier(
                        new NamespacedKey(host, "summer_damage"),
                        damage,
                        AttributeModifier.Operation.ADD_NUMBER,
                        EquipmentSlotGroup.OFFHAND
                )
        );

        meta.addAttributeModifier(
                Attribute.ARMOR,
                new AttributeModifier(
                        new NamespacedKey(host, "summer_armor"),
                        armor,
                        AttributeModifier.Operation.ADD_NUMBER,
                        EquipmentSlotGroup.OFFHAND
                )
        );

        if (getConfig().getBoolean("summer.item.hide-vanilla-attributes", true)) {
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        }

        String texture = getConfig().getString("summer.item.texture-value", "");
        if (texture != null && !texture.trim().isEmpty()) {
            applyTexture(meta, texture.trim());
        }

        item.setItemMeta(meta);
        return item;
    }

    public ItemStack createShullerBall() {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD, 1);
        ItemMeta rawMeta = item.getItemMeta();
        if (!(rawMeta instanceof SkullMeta)) {
            return item;
        }

        SkullMeta meta = (SkullMeta) rawMeta;
        double damage = d("shuller.item.attributes.damage", 5.5, -100.0, 100.0);
        double speedPercent = d("shuller.item.attributes.speed-percent", 30.0, -95.0, 500.0);

        meta.setDisplayName(formatShullerItem(getConfig().getString("shuller.item.name", "&4&lШар &f &4Проклятье Утраты"), damage, speedPercent));

        List<String> lore = new ArrayList<String>();
        for (String line : getConfig().getStringList("shuller.item.lore")) {
            lore.add(formatShullerItem(line, damage, speedPercent));
        }
        meta.setLore(lore);

        meta.getPersistentDataContainer().set(shullerKey, PersistentDataType.BYTE, (byte) 1);

        meta.addAttributeModifier(
                Attribute.ATTACK_DAMAGE,
                new AttributeModifier(
                        new NamespacedKey(host, "shuller_damage"),
                        damage,
                        AttributeModifier.Operation.ADD_NUMBER,
                        EquipmentSlotGroup.OFFHAND
                )
        );

        meta.addAttributeModifier(
                Attribute.MOVEMENT_SPEED,
                new AttributeModifier(
                        new NamespacedKey(host, "shuller_speed"),
                        speedPercent / 100.0,
                        AttributeModifier.Operation.MULTIPLY_SCALAR_1,
                        EquipmentSlotGroup.OFFHAND
                )
        );

        if (getConfig().getBoolean("shuller.item.hide-vanilla-attributes", true)) {
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        }

        String texture = getConfig().getString("shuller.item.texture-value", "");
        if (texture != null && !texture.trim().isEmpty()) {
            applyTexture(meta, texture.trim());
        }

        item.setItemMeta(meta);
        return item;
    }

    @EventHandler(ignoreCancelled = true)
    public void onSneak(PlayerToggleSneakEvent event) {
        // Only the press itself. Releasing SHIFT does nothing.
        if (!event.isSneaking()) {
            return;
        }

        Player player = event.getPlayer();
        ItemStack offHand = player.getInventory().getItemInOffHand();

        if (isSummer(offHand)) {
            activateSummer(player);
            return;
        }

        if (isShuller(offHand)) {
            activateShuller(player);
        }
    }

    private void activateSummer(Player player) {
        if (!checkAndStartCooldown(
                player,
                summerCooldowns,
                "summer.ability.cooldown-seconds",
                "summer.ability.cooldown-message",
                "summer.ability.name",
                "Солнечный Солнцеворот"
        )) {
            return;
        }

        Location center = player.getLocation().clone();
        summerHeat(player);
        summerPortal(center);
        summerMelonRain(player, center);
    }

    private void activateShuller(final Player player) {
        if (!checkAndStartCooldown(
                player,
                shullerCooldowns,
                "shuller.ability.cooldown-seconds",
                "shuller.ability.cooldown-message",
                "shuller.ability.name",
                "Финальный Трюк"
        )) {
            return;
        }

        /*
         * origin всегда остаётся точкой, где игрок нажал SHIFT.
         * Иллюзия и дебаффы создаются именно там.
         *
         * Если teleport-behind позже будет включён в config,
         * сам игрок телепортируется за выбранную цель уже после
         * сохранения этой точки.
         */
        final Location origin = player.getLocation().clone();
        final World world = origin.getWorld();
        if (world == null) {
            return;
        }

        playSound(world, origin, "shuller.ability.sounds.witch", Sound.ENTITY_WITCH_CELEBRATE);
        playSound(world, origin, "shuller.ability.sounds.cave", Sound.AMBIENT_CAVE);

        applyShullerDebuffs(player, origin);
        createShullerIllusion(origin);

        /*
         * Функция полностью готова, но по умолчанию выключена
         * через shuller.ability.teleport-behind.enabled: false
         */
        tryShullerTeleportBehind(player);

        int invisibilityTicks = secondsToTicks(
                d("shuller.ability.invisibility-seconds", 3.5, 0.1, 30.0),
                1,
                600
        );

        int speedLevel = i("shuller.ability.speed-level", 3, 1, 10);
        player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, invisibilityTicks, 0, false, false));
        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, invisibilityTicks, speedLevel - 1, false, false));

        if (getConfig().getBoolean("shuller.ability.full-hide", true)) {
            hideShullerPlayer(player, invisibilityTicks);
        }

        /*
         * Strength V на ОДИН успешный ближний удар.
         */
        grantShullerStrength(player);

        /*
         * Большое тёмное облако следует за игроком.
         */
        createShullerCloud(player);
    }

    private boolean checkAndStartCooldown(
            Player player,
            Map<UUID, Long> cooldownMap,
            String secondsPath,
            String messagePath,
            String abilityNamePath,
            String fallbackAbility
    ) {
        long now = System.currentTimeMillis();
        long cooldownMillis = i(secondsPath, 300, 1, 86400) * 1000L;
        Long previous = cooldownMap.get(player.getUniqueId());

        if (previous != null && now - previous < cooldownMillis) {
            long remaining = (cooldownMillis - (now - previous) + 999L) / 1000L;
            String ability = getConfig().getString(abilityNamePath, fallbackAbility);
            if (ability == null) {
                ability = fallbackAbility;
            }
            String message = getConfig().getString(
                    messagePath,
                    "&6Задержка: &fВы сможете использовать &6%ability% &fчерез &c%time% секунд."
            );
            if (message == null) {
                message = "&6Задержка: &fВы сможете использовать &6%ability% &fчерез &c%time% секунд.";
            }
            player.sendMessage(color(
                    message.replace("%ability%", ability)
                            .replace("%time%", String.valueOf(remaining))
            ));
            return false;
        }

        cooldownMap.put(player.getUniqueId(), now);
        return true;
    }

    // ============================================================
    // SUMMER
    // ============================================================

    private void summerHeat(Player player) {
        Location center = player.getLocation().clone();
        World world = center.getWorld();
        if (world == null) {
            return;
        }

        playSound(world, center, "summer.ability.sounds.firecharge", Sound.ITEM_FIRECHARGE_USE);
        playSound(world, center, "summer.ability.sounds.blast", Sound.ENTITY_FIREWORK_ROCKET_BLAST);
        summerFireRing(player);

        double radius = d("summer.ability.heat.radius", 7.0, 0.0, 30.0);
        double damage = d("summer.ability.heat.damage-hearts", 2.5, 0.0, 20.0) * 2.0;
        int fireTicks = i("summer.ability.heat.fire-seconds", 6, 0, 60) * 20;

        for (Entity entity : player.getNearbyEntities(radius, radius, radius)) {
            if (!(entity instanceof LivingEntity) || entity.equals(player)) {
                continue;
            }

            LivingEntity target = (LivingEntity) entity;
            if (target.getLocation().distanceSquared(center) > radius * radius) {
                continue;
            }

            applyHeatDamage(target, player, damage, fireTicks);
        }
    }

    // Keep true damage, but let protection plugins cancel the actual damage event.
    private void applyHeatDamage(LivingEntity target, LivingEntity owner, double damage, int fireTicks) {
        if (target.isDead() || target.isInvulnerable()) return;
        if (target instanceof Player p && (p.getGameMode() == GameMode.CREATIVE
                || p.getGameMode() == GameMode.SPECTATOR || !p.getWorld().getPVP())) return;
        HeatHit hit = new HeatHit(owner);
        heatHits.put(target.getUniqueId(), hit);
        try {
            if (damage > 0.0) target.damage(damage, org.bukkit.damage.DamageSource
                    .builder(org.bukkit.damage.DamageType.MAGIC)
                    .withCausingEntity(owner).withDirectEntity(owner).build());
        } finally {
            heatHits.remove(target.getUniqueId());
        }
        if (hit.accepted && !target.isDead() && fireTicks > 0) {
            org.bukkit.event.entity.EntityCombustByEntityEvent combust =
                    new org.bukkit.event.entity.EntityCombustByEntityEvent(owner, target, fireTicks / 20.0f);
            Bukkit.getPluginManager().callEvent(combust);
            if (!combust.isCancelled()) target.setFireTicks(Math.max(target.getFireTicks(),
                    Math.round(combust.getDuration() * 20)));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHeatDamage(EntityDamageByEntityEvent event) {
        HeatHit hit = heatHits.get(event.getEntity().getUniqueId());
        if (hit == null || !hit.owner.equals(event.getDamager())) return;
        for (org.bukkit.event.entity.EntityDamageEvent.DamageModifier modifier
                : org.bukkit.event.entity.EntityDamageEvent.DamageModifier.values()) {
            if (modifier != org.bukkit.event.entity.EntityDamageEvent.DamageModifier.BASE
                    && event.isApplicable(modifier)) event.setDamage(modifier, 0.0);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeatAccepted(EntityDamageByEntityEvent event) {
        HeatHit hit = heatHits.get(event.getEntity().getUniqueId());
        if (hit != null && hit.owner.equals(event.getDamager()) && event.getFinalDamage() > 0)
            hit.accepted = true;
    }

    private void summerFireRing(final Player player) {
        if (!getConfig().getBoolean("summer.ability.fire-ring.enabled", true)) {
            return;
        }

        final int steps = i("summer.ability.fire-ring.steps", 5, 1, 20);
        final int points = i("summer.ability.fire-ring.points", 30, 8, 120);
        final double startRadius = d("summer.ability.fire-ring.start-radius", 0.8, 0.1, 10.0);
        final double radiusStep = d("summer.ability.fire-ring.radius-step", 0.65, 0.01, 5.0);
        final Location origin = player.getLocation().clone().add(0.0, 1.0, 0.0);
        final World world = origin.getWorld();
        if (world == null) {
            return;
        }

        new BukkitRunnable() {
            private int step = 0;

            @Override
            public void run() {
                if (step >= steps) {
                    cancel();
                    return;
                }

                double radius = startRadius + step * radiusStep;
                for (int point = 0; point < points; point++) {
                    double angle = Math.PI * 2.0 * point / points;
                    Location location = origin.clone().add(
                            Math.cos(angle) * radius,
                            0.0,
                            Math.sin(angle) * radius
                    );

                    world.spawnParticle(Particle.FLAME, location, 1, 0.02, 0.02, 0.02, 0.0);
                    if (point % 4 == 0) {
                        world.spawnParticle(Particle.LAVA, location, 1, 0.01, 0.01, 0.01, 0.0);
                    }
                }
                step++;
            }
        }.runTaskTimer(host, 0L, 1L);
    }

    private void summerPortal(final Location center) {
        if (!getConfig().getBoolean("summer.ability.portal.enabled", true)) {
            return;
        }

        final int duration = i("summer.ability.portal.duration-ticks", 20, 1, 100);
        final double height = d("summer.ability.portal.height", 12.0, 1.0, 40.0);
        final double maxRadius = d("summer.ability.portal.radius", 2.5, 0.5, 10.0);
        final int points = i("summer.ability.portal.points", 24, 8, 120);
        final World world = center.getWorld();
        if (world == null) {
            return;
        }

        new BukkitRunnable() {
            private int tick = 0;

            @Override
            public void run() {
                if (tick >= duration) {
                    cancel();
                    return;
                }

                Location portalCenter = center.clone().add(0.0, height, 0.0);
                double progress = Math.min(1.0, (tick + 1.0) / Math.max(1.0, duration * 0.55));
                double radius = Math.max(0.2, maxRadius * progress);

                for (int point = 0; point < points; point++) {
                    double angle = Math.PI * 2.0 * point / points;
                    Location location = portalCenter.clone().add(
                            Math.cos(angle) * radius,
                            0.0,
                            Math.sin(angle) * radius
                    );

                    world.spawnParticle(Particle.FLAME, location, 1, 0.01, 0.01, 0.01, 0.0);
                    if (point % 5 == 0) {
                        world.spawnParticle(Particle.LAVA, location, 1, 0.01, 0.01, 0.01, 0.0);
                    }
                }
                tick++;
            }
        }.runTaskTimer(host, 0L, 1L);
    }

    private void summerMelonRain(final Player owner, final Location center) {
        if (!getConfig().getBoolean("summer.ability.melon-rain.enabled", true)) {
            return;
        }

        final int total = i("summer.ability.melon-rain.total-melons", 50, 1, 200);
        final int perWave = i("summer.ability.melon-rain.melons-per-wave", 7, 1, 20);
        final long interval = i("summer.ability.melon-rain.interval-ticks", 8, 1, 100);
        final int waves = (int) Math.ceil((double) total / (double) perWave);

        summerRedRing(center, waves * interval + 120L);

        new BukkitRunnable() {
            private int spawned = 0;

            @Override
            public void run() {
                if (!owner.isOnline() || owner.isDead() || spawned >= total) {
                    cancel();
                    return;
                }

                int amount = Math.min(perWave, total - spawned);
                summerSpawnWave(owner, center, amount);
                spawned += amount;

                if (spawned >= total) {
                    cancel();
                }
            }
        }.runTaskTimer(host, 0L, interval);
    }

    private void summerSpawnWave(Player owner, Location center, int amount) {
        World world = center.getWorld();
        if (world == null) {
            return;
        }

        double radius = d("summer.ability.melon-rain.radius", 10.0, 1.0, 30.0);
        double minHeight = d("summer.ability.melon-rain.spawn-height-min", 12.0, 4.0, 40.0);
        double maxHeight = d("summer.ability.melon-rain.spawn-height-max", 16.0, 4.0, 45.0);

        if (maxHeight < minHeight) {
            double temporary = minHeight;
            minHeight = maxHeight;
            maxHeight = temporary;
        }

        for (int number = 0; number < amount; number++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double distance = Math.sqrt(random.nextDouble()) * radius;
            double height = minHeight + random.nextDouble() * Math.max(0.01, maxHeight - minHeight);

            Location spawn = center.clone().add(
                    Math.cos(angle) * distance,
                    height,
                    Math.sin(angle) * distance
            );

            FallingBlock melon = world.spawnFallingBlock(spawn, Material.MELON.createBlockData());
            melon.setHurtEntities(false);
            melon.setDropItem(false);
            melon.setPersistent(false);
            melon.setVelocity(new Vector(0.0, -0.08 - random.nextDouble() * 0.04, 0.0));

            melons.put(
                    melon.getUniqueId(),
                    new ActiveMelon(
                            melon,
                            owner.getUniqueId(),
                            System.currentTimeMillis(),
                            melon.getBoundingBox().clone()
                    )
            );
        }

        ensureMelonTask();
    }

    private void summerRedRing(final Location center, final long durationTicks) {
        if (!getConfig().getBoolean("summer.ability.melon-rain.red-ring.enabled", true)) {
            return;
        }

        final double radius = d("summer.ability.melon-rain.radius", 10.0, 1.0, 30.0);
        final double height = d("summer.ability.melon-rain.red-ring.height", 12.0, 1.0, 40.0);
        final int points = i("summer.ability.melon-rain.red-ring.points", 72, 12, 180);
        final int refresh = i("summer.ability.melon-rain.red-ring.refresh-ticks", 4, 1, 20);
        final float particleSize = (float) d("summer.ability.melon-rain.red-ring.particle-size", 1.25, 0.2, 4.0);
        final World world = center.getWorld();
        if (world == null) {
            return;
        }

        final Particle.DustOptions red = new Particle.DustOptions(Color.fromRGB(255, 0, 0), particleSize);
        final Location ringCenter = center.clone().add(0.0, height, 0.0);

        new BukkitRunnable() {
            private long lived = 0L;

            @Override
            public void run() {
                if (lived > durationTicks) {
                    cancel();
                    return;
                }

                for (int point = 0; point < points; point++) {
                    double angle = Math.PI * 2.0 * point / points;
                    Location location = ringCenter.clone().add(
                            Math.cos(angle) * radius,
                            0.0,
                            Math.sin(angle) * radius
                    );
                    world.spawnParticle(
                            Particle.DUST,
                            location,
                            1,
                            0.0,
                            0.0,
                            0.0,
                            0.0,
                            red,
                            true
                    );
                }
                lived += refresh;
            }
        }.runTaskTimer(host, 0L, refresh);
    }

    private void ensureMelonTask() {
        if (melonTask != null) {
            return;
        }

        melonTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (melons.isEmpty()) {
                    cancel();
                    melonTask = null;
                    return;
                }

                long now = System.currentTimeMillis();
                double expansion = d("summer.ability.melon-rain.hitbox-expansion", 0.18, 0.0, 1.0);
                Iterator<Map.Entry<UUID, ActiveMelon>> iterator = melons.entrySet().iterator();

                while (iterator.hasNext()) {
                    ActiveMelon active = iterator.next().getValue();
                    FallingBlock melon = active.entity;

                    if (melon == null || !melon.isValid() || melon.isDead()) {
                        iterator.remove();
                        continue;
                    }

                    if (now - active.spawnedAt > 10000L) {
                        Location impact = melon.getLocation().clone();
                        melon.remove();
                        iterator.remove();
                        summerShatter(impact);
                        continue;
                    }

                    BoundingBox currentBox = melon.getBoundingBox().clone();
                    BoundingBox sweptBox = currentBox.clone();
                    if (active.previousBox != null) {
                        sweptBox.union(active.previousBox);
                    }
                    sweptBox.expand(expansion, expansion, expansion);
                    active.previousBox = currentBox;

                    Collection<Entity> nearby = melon.getWorld().getNearbyEntities(sweptBox);
                    for (Entity entity : nearby) {
                        if (!(entity instanceof LivingEntity)) {
                            continue;
                        }
                        if (entity.getUniqueId().equals(active.ownerId)) {
                            continue;
                        }

                        LivingEntity target = (LivingEntity) entity;
                        if (target.isDead() || !sweptBox.overlaps(target.getBoundingBox())) {
                            continue;
                        }

                        double damage = d("summer.ability.melon-rain.damage-hearts", 1.5, 0.0, 20.0) * 2.0;
                        Player owner = Bukkit.getPlayer(active.ownerId);

                        if (damage > 0.0) {
                            if (owner != null && owner.isOnline()) {
                                target.damage(damage, owner);
                            } else {
                                target.damage(damage);
                            }
                        }

                        Location impact = melon.getLocation().clone();
                        melon.remove();
                        iterator.remove();
                        summerShatter(impact);
                        break;
                    }
                }
            }
        }.runTaskTimer(host, 1L, 1L);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onMelonLand(EntityChangeBlockEvent event) {
        ActiveMelon active = melons.remove(event.getEntity().getUniqueId());
        if (active == null) {
            return;
        }

        event.setCancelled(true);
        Location impact = event.getBlock().getLocation().clone().add(0.5, 0.25, 0.5);
        if (event.getEntity().isValid()) {
            event.getEntity().remove();
        }

        summerShatter(impact);
        stopMelonTaskIfEmpty();
    }

    private void summerShatter(Location location) {
        World world = location.getWorld();
        if (world == null) {
            return;
        }

        int sliceCount = i("summer.ability.melon-rain.slices-per-melon", 3, 0, 6);
        int lifeTicks = i("summer.ability.melon-rain.slice-lifetime-ticks", 14, 4, 80);

        world.spawnParticle(
                Particle.ITEM,
                location,
                12,
                0.30,
                0.22,
                0.30,
                0.10,
                new ItemStack(Material.MELON_SLICE)
        );

        long expires = System.currentTimeMillis() + lifeTicks * 50L;
        for (int number = 0; number < sliceCount; number++) {
            Item slice = world.dropItem(location, new ItemStack(Material.MELON_SLICE, 1));
            slice.setPickupDelay(Integer.MAX_VALUE);
            slice.setPersistent(false);
            slice.setVelocity(new Vector(
                    (random.nextDouble() - 0.5) * 0.45,
                    0.18 + random.nextDouble() * 0.22,
                    (random.nextDouble() - 0.5) * 0.45
            ));
            slices.put(slice.getUniqueId(), new ActiveSlice(slice, expires));
        }

        if (sliceCount > 0) {
            ensureSliceTask();
        }
    }

    private void ensureSliceTask() {
        if (sliceTask != null) {
            return;
        }

        sliceTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (slices.isEmpty()) {
                    cancel();
                    sliceTask = null;
                    return;
                }

                long now = System.currentTimeMillis();
                Iterator<Map.Entry<UUID, ActiveSlice>> iterator = slices.entrySet().iterator();
                while (iterator.hasNext()) {
                    ActiveSlice active = iterator.next().getValue();
                    if (active.entity == null || !active.entity.isValid() || active.entity.isDead() || now >= active.expiresAt) {
                        if (active.entity != null && active.entity.isValid()) {
                            active.entity.remove();
                        }
                        iterator.remove();
                    }
                }
            }
        }.runTaskTimer(host, 2L, 2L);
    }

    private void stopMelonTaskIfEmpty() {
        if (!melons.isEmpty()) {
            return;
        }
        if (melonTask != null) {
            melonTask.cancel();
            melonTask = null;
        }
    }

    // ============================================================
    // SHULLER
    // ============================================================

    /*
     * ============================================================
     * SHULLER — STRENGTH НА ОДИН УДАР
     * ============================================================
     */

    private void grantShullerStrength(final Player player) {
        if (!getConfig().getBoolean("shuller.ability.strength-on-hit.enabled", true)) {
            return;
        }

        final UUID playerId = player.getUniqueId();

        if (pendingShullerStrength.containsKey(playerId)) {
            clearShullerStrength(playerId, true);
        }

        final int level = i("shuller.ability.strength-on-hit.level", 5, 1, 20);
        final int timeoutTicks = secondsToTicks(
                d("shuller.ability.strength-on-hit.timeout-seconds", 30.0, 1.0, 300.0),
                20,
                6000
        );

        PotionEffect previous = player.getPotionEffect(PotionEffectType.STRENGTH);

        PendingStrength pending = new PendingStrength(
                previous,
                System.currentTimeMillis(),
                timeoutTicks,
                UUID.randomUUID()
        );

        pendingShullerStrength.put(playerId, pending);

        /*
         * Strength V = amplifier 4.
         * particles=false, чтобы обычные potion particles
         * не мешали эффекту облака.
         */
        player.addPotionEffect(
                new PotionEffect(
                        PotionEffectType.STRENGTH,
                        timeoutTicks,
                        level - 1,
                        false,
                        false
                ),
                true
        );

        final UUID token = pending.token;

        /*
         * Safety timeout: если игрок никого не ударил,
         * наш Strength всё равно будет убран.
         */
        new BukkitRunnable() {
            @Override
            public void run() {
                PendingStrength current = pendingShullerStrength.get(playerId);
                if (current == null || !current.token.equals(token)) {
                    return;
                }
                clearShullerStrength(playerId, true);
            }
        }.runTaskLater(host, timeoutTicks);
    }

    /*
     * MONITOR + ignoreCancelled=true:
     * заряд тратится только на реально прошедший melee-hit,
     * а не на отменённую атаку.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShullerStrengthHit(EntityDamageByEntityEvent event) {
        if (event.getCause() != org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_ATTACK
                && event.getCause() != org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK) {
            return;
        }
        if (!(event.getDamager() instanceof Player)) {
            return;
        }

        Player attacker = (Player) event.getDamager();
        UUID attackerId = attacker.getUniqueId();

        if (!pendingShullerStrength.containsKey(attackerId)) {
            return;
        }

        if (event.getFinalDamage() <= 0.0) {
            return;
        }

        /*
         * Этот удар уже был рассчитан сервером со Strength V.
         * После него эффект снимается.
         */
        clearShullerStrength(attackerId, true);
    }

    private void clearShullerStrength(UUID playerId, boolean restorePrevious) {
        PendingStrength pending = pendingShullerStrength.remove(playerId);
        if (pending == null) {
            return;
        }

        Player player = Bukkit.getPlayer(playerId);
        if (player == null) {
            return;
        }

        player.removePotionEffect(PotionEffectType.STRENGTH);

        if (!restorePrevious || pending.previousEffect == null) {
            return;
        }

        /*
         * Возвращаем Strength, который был ДО SHULLER,
         * с поправкой на уже прошедшее время.
         */
        long elapsedMillis = Math.max(
                0L,
                System.currentTimeMillis() - pending.appliedAtMillis
        );

        int elapsedTicks = (int) Math.min(
                Integer.MAX_VALUE,
                elapsedMillis / 50L
        );

        int remainingTicks = pending.previousEffect.isInfinite() ? -1
                : pending.previousEffect.getDuration() - elapsedTicks;

        if (remainingTicks <= 0 && !pending.previousEffect.isInfinite()) {
            return;
        }

        player.addPotionEffect(
                new PotionEffect(
                        pending.previousEffect.getType(),
                        remainingTicks,
                        pending.previousEffect.getAmplifier(),
                        pending.previousEffect.isAmbient(),
                        pending.previousEffect.hasParticles(),
                        pending.previousEffect.hasIcon()
                ),
                true
        );
    }

    /*
     * ============================================================
     * SHULLER — БОЛЬШОЕ ОБЛАКО ВОКРУГ ИГРОКА
     * ============================================================
     */

    private void createShullerCloud(final Player player) {
        if (!getConfig().getBoolean("shuller.ability.cloud.enabled", true)) {
            return;
        }

        final UUID playerId = player.getUniqueId();

        final int duration =
                i("shuller.ability.cloud.duration-ticks", 70, 1, 400);

        final int refresh =
                i("shuller.ability.cloud.refresh-ticks", 2, 1, 20);

        final double horizontalRadius =
                d("shuller.ability.cloud.horizontal-radius", 4.5, 0.5, 12.0);

        final double verticalRadius =
                d("shuller.ability.cloud.vertical-radius", 2.8, 0.5, 8.0);

        final int largeSmoke =
                i("shuller.ability.cloud.particles.large-smoke", 34, 0, 120);

        final int normalSmoke =
                i("shuller.ability.cloud.particles.normal-smoke", 30, 0, 120);

        final int ink =
                i("shuller.ability.cloud.particles.squid-ink", 24, 0, 120);

        final int whiteAsh =
                i("shuller.ability.cloud.particles.white-ash", 24, 0, 120);

        final int soulFlame =
                i("shuller.ability.cloud.particles.soul-fire", 12, 0, 80);

        new BukkitRunnable() {
            private int lived = 0;

            @Override
            public void run() {
                Player current = Bukkit.getPlayer(playerId);

                if (current == null
                        || !current.isOnline()
                        || current.isDead()
                        || lived >= duration) {

                    cancel();
                    return;
                }

                World world = current.getWorld();
                Location center =
                        current.getLocation()
                                .clone()
                                .add(0.0, 1.0, 0.0);

                /*
                 * Тёмный объём как на референсе:
                 * чёрный/серый дым + белый пепел + голубые soul flames.
                 * Всего 5 spawnParticle-вызовов за refresh.
                 */
                if (largeSmoke > 0) {
                    world.spawnParticle(
                            Particle.LARGE_SMOKE,
                            center,
                            largeSmoke,
                            horizontalRadius,
                            verticalRadius,
                            horizontalRadius,
                            0.01
                    );
                }

                if (normalSmoke > 0) {
                    world.spawnParticle(
                            Particle.SMOKE,
                            center,
                            normalSmoke,
                            horizontalRadius,
                            verticalRadius,
                            horizontalRadius,
                            0.02
                    );
                }

                if (ink > 0) {
                    world.spawnParticle(
                            Particle.SQUID_INK,
                            center,
                            ink,
                            horizontalRadius * 0.85,
                            verticalRadius * 0.90,
                            horizontalRadius * 0.85,
                            0.01
                    );
                }

                if (whiteAsh > 0) {
                    world.spawnParticle(
                            Particle.WHITE_ASH,
                            center,
                            whiteAsh,
                            horizontalRadius,
                            verticalRadius,
                            horizontalRadius,
                            0.01
                    );
                }

                if (soulFlame > 0) {
                    world.spawnParticle(
                            Particle.SOUL_FIRE_FLAME,
                            center,
                            soulFlame,
                            horizontalRadius * 0.85,
                            verticalRadius * 0.85,
                            horizontalRadius * 0.85,
                            0.01
                    );
                }

                lived += refresh;
            }
        }.runTaskTimer(host, 0L, refresh);
    }

    /*
     * ============================================================
     * SHULLER — ТЕЛЕПОРТ ЗА ПРОТИВНИКА
     *
     * Код готов, но DEFAULT В CONFIG = false.
     * ============================================================
     */

    private void tryShullerTeleportBehind(Player player) {
        if (!getConfig().getBoolean("shuller.ability.teleport-behind.enabled", false)) {
            return;
        }

        Player target = findShullerTeleportTarget(player);
        if (target == null) {
            return;
        }

        double behindDistance =
                d("shuller.ability.teleport-behind.behind-distance", 1.5, 0.5, 5.0);

        Location targetLocation = target.getLocation().clone();

        Vector backward = targetLocation.getDirection().setY(0.0);

        if (backward.lengthSquared() < 0.0001) {
            backward = new Vector(0.0, 0.0, 1.0);
        } else {
            backward.normalize();
        }

        Location desired = targetLocation.clone().subtract(
                backward.multiply(behindDistance)
        );

        Vector look = target.getEyeLocation()
                .toVector()
                .subtract(
                        desired.clone()
                                .add(0.0, player.getEyeHeight(), 0.0)
                                .toVector()
                );

        if (look.lengthSquared() > 0.0001) {
            desired.setDirection(look);
        }

        Location safe = findSafeTeleportLocation(desired);
        if (safe == null) {
            return;
        }

        player.teleport(safe);
    }

    private Player findShullerTeleportTarget(Player player) {
        double range =
                d("shuller.ability.teleport-behind.range", 12.0, 1.0, 40.0);

        double maxAngle =
                d("shuller.ability.teleport-behind.max-angle-degrees", 70.0, 5.0, 180.0);

        boolean requireLineOfSight =
                getConfig().getBoolean(
                        "shuller.ability.teleport-behind.require-line-of-sight",
                        true
                );

        Location eye = player.getEyeLocation();
        Vector look = eye.getDirection().normalize();

        Player best = null;
        double bestDistanceSquared = Double.MAX_VALUE;

        for (Player candidate : Bukkit.getOnlinePlayers()) {
            if (candidate.getUniqueId().equals(player.getUniqueId())) {
                continue;
            }

            if (!candidate.getWorld().equals(player.getWorld())) {
                continue;
            }

            if (candidate.isDead()) {
                continue;
            }

            double distanceSquared = candidate.getLocation()
                    .distanceSquared(player.getLocation());

            if (distanceSquared > range * range) {
                continue;
            }

            if (requireLineOfSight && !player.hasLineOfSight(candidate)) {
                continue;
            }

            Vector toTarget = candidate.getEyeLocation()
                    .toVector()
                    .subtract(eye.toVector());

            if (toTarget.lengthSquared() < 0.0001) {
                continue;
            }

            toTarget.normalize();

            double dot = Math.max(
                    -1.0,
                    Math.min(
                            1.0,
                            look.dot(toTarget)
                    )
            );

            double angle = Math.toDegrees(Math.acos(dot));

            if (angle > maxAngle) {
                continue;
            }

            if (distanceSquared < bestDistanceSquared) {
                bestDistanceSquared = distanceSquared;
                best = candidate;
            }
        }

        return best;
    }

    private Location findSafeTeleportLocation(Location desired) {
        World world = desired.getWorld();
        if (world == null) {
            return null;
        }

        int[] offsets = new int[]{0, 1, -1, 2};

        for (int offset : offsets) {
            Location test = desired.clone().add(0.0, offset, 0.0);

            Material feet = test.getBlock().getType();
            Material head = test.clone().add(0.0, 1.0, 0.0).getBlock().getType();
            Material floor = test.clone().add(0.0, -1.0, 0.0).getBlock().getType();

            if (feet.isSolid()) {
                continue;
            }

            if (head.isSolid()) {
                continue;
            }

            if (!floor.isSolid()) {
                continue;
            }

            test.setX(test.getBlockX() + 0.5);
            test.setZ(test.getBlockZ() + 0.5);

            return test;
        }

        return null;
    }

    private void applyShullerDebuffs(Player owner, Location origin) {
        double radius = d("shuller.ability.debuff.radius", 8.0, 0.0, 30.0);
        int blindnessTicks = secondsToTicks(
                d("shuller.ability.debuff.blindness-seconds", 3.0, 0.0, 30.0),
                0,
                600
        );
        int nauseaTicks = secondsToTicks(
                d("shuller.ability.debuff.nausea-seconds", 3.0, 0.0, 30.0),
                0,
                600
        );
        int blindnessLevel = i("shuller.ability.debuff.blindness-level", 1, 1, 10);
        int nauseaLevel = i("shuller.ability.debuff.nausea-level", 1, 1, 10);
        boolean affectPlayers = getConfig().getBoolean("shuller.ability.debuff.affect-players", true);
        boolean affectMobs = getConfig().getBoolean("shuller.ability.debuff.affect-mobs", true);

        World world = origin.getWorld();
        if (world == null) {
            return;
        }

        for (Entity entity : world.getNearbyEntities(origin, radius, radius, radius)) {
            if (!(entity instanceof LivingEntity)) {
                continue;
            }
            if (entity.getUniqueId().equals(owner.getUniqueId())) {
                continue;
            }
            if (entity instanceof Player && !affectPlayers) {
                continue;
            }
            if (!(entity instanceof Player) && !affectMobs) {
                continue;
            }
            if (entity.getLocation().distanceSquared(origin) > radius * radius) {
                continue;
            }

            LivingEntity target = (LivingEntity) entity;
            if (blindnessTicks > 0) {
                target.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, blindnessTicks, blindnessLevel - 1));
            }
            if (nauseaTicks > 0) {
                target.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, nauseaTicks, nauseaLevel - 1));
            }
        }
    }

    private void hideShullerPlayer(final Player player, final int durationTicks) {
        final UUID playerId = player.getUniqueId();
        final long until = System.currentTimeMillis() + durationTicks * 50L;
        hiddenShullerUntil.put(playerId, until);

        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (!viewer.getUniqueId().equals(playerId)) {
                viewer.hidePlayer(host, player);
            }
        }

        new BukkitRunnable() {
            @Override
            public void run() {
                Long currentUntil = hiddenShullerUntil.get(playerId);
                if (currentUntil == null || currentUntil.longValue() != until) {
                    return;
                }
                restoreShullerVisibility(playerId);
            }
        }.runTaskLater(host, durationTicks);
    }

    private void restoreShullerVisibility(UUID playerId) {
        hiddenShullerUntil.remove(playerId);
        Player hidden = Bukkit.getPlayer(playerId);
        if (hidden == null) {
            return;
        }

        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (!viewer.getUniqueId().equals(playerId)) {
                viewer.showPlayer(host, hidden);
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, Long>> iterator = hiddenShullerUntil.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<UUID, Long> entry = iterator.next();
            if (entry.getValue() <= now) {
                iterator.remove();
                continue;
            }

            Player hidden = Bukkit.getPlayer(entry.getKey());
            if (hidden != null && !hidden.getUniqueId().equals(event.getPlayer().getUniqueId())) {
                event.getPlayer().hidePlayer(host, hidden);
            }
        }
    }

    private void createShullerIllusion(final Location origin) {
        if (!getConfig().getBoolean("shuller.ability.illusion.enabled", true)) {
            return;
        }

        final World world = origin.getWorld();
        if (world == null) {
            return;
        }

        final int duration = i("shuller.ability.illusion.duration-ticks", 70, 1, 400);
        final int refresh = i("shuller.ability.illusion.particles.refresh-ticks", 2, 1, 20);
        final int soulCount = i("shuller.ability.illusion.particles.soul-count", 8, 0, 60);
        final int inkCount = i("shuller.ability.illusion.particles.ink-count", 5, 0, 60);
        final int smokeCount = i("shuller.ability.illusion.particles.smoke-count", 8, 0, 60);
        final double spread = d("shuller.ability.illusion.particles.spread", 0.65, 0.0, 3.0);
        final int ringPoints = i("shuller.ability.illusion.particles.ring-points", 20, 0, 80);
        final double ringRadius = d("shuller.ability.illusion.particles.ring-radius", 1.8, 0.1, 8.0);
        final int batCount = i("shuller.ability.illusion.bats.count", 6, 0, 16);
        final double batRadius = d("shuller.ability.illusion.bats.spawn-radius", 2.0, 0.0, 8.0);
        final List<Entity> visuals = new ArrayList<Entity>();

        if (getConfig().getBoolean("shuller.ability.illusion.armor-stand.enabled", true)) {
            ArmorStand stand = (ArmorStand) world.spawnEntity(origin.clone(), EntityType.ARMOR_STAND);
            stand.setVisible(false);
            stand.setGravity(false);
            stand.setMarker(true);
            stand.setBasePlate(false);
            stand.setArms(false);
            stand.setInvulnerable(true);
            stand.setSilent(true);
            stand.setPersistent(false);

            Material pumpkin = material(
                    getConfig().getString("shuller.ability.illusion.armor-stand.helmet", "CARVED_PUMPKIN"),
                    Material.CARVED_PUMPKIN
            );
            if (stand.getEquipment() != null) {
                stand.getEquipment().setHelmet(new ItemStack(pumpkin));
            }

            visuals.add(stand);
            shullerVisualEntities.add(stand);
        }

        for (int index = 0; index < batCount; index++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double distance = random.nextDouble() * batRadius;
            Location batLocation = origin.clone().add(
                    Math.cos(angle) * distance,
                    0.8 + random.nextDouble() * 1.8,
                    Math.sin(angle) * distance
            );

            Bat bat = (Bat) world.spawnEntity(batLocation, EntityType.BAT);
            bat.setPersistent(false);
            bat.setInvulnerable(true);
            bat.setSilent(true);
            visuals.add(bat);
            shullerVisualEntities.add(bat);
        }

        world.spawnParticle(Particle.LARGE_SMOKE, origin.clone().add(0.0, 1.0, 0.0), 16, 0.6, 0.9, 0.6, 0.03);
        world.spawnParticle(Particle.SOUL_FIRE_FLAME, origin.clone().add(0.0, 1.0, 0.0), 12, 0.7, 0.8, 0.7, 0.02);
        world.spawnParticle(Particle.SQUID_INK, origin.clone().add(0.0, 1.0, 0.0), 8, 0.6, 0.8, 0.6, 0.01);

        new BukkitRunnable() {
            private int lived = 0;

            @Override
            public void run() {
                if (lived >= duration) {
                    cleanupShullerVisuals(visuals);
                    cancel();
                    return;
                }

                Location effectCenter = origin.clone().add(0.0, 1.0, 0.0);

                if (soulCount > 0) {
                    world.spawnParticle(
                            Particle.SOUL_FIRE_FLAME,
                            effectCenter,
                            soulCount,
                            spread,
                            spread,
                            spread,
                            0.01
                    );
                }
                if (inkCount > 0) {
                    world.spawnParticle(
                            Particle.SQUID_INK,
                            effectCenter,
                            inkCount,
                            spread,
                            spread,
                            spread,
                            0.01
                    );
                }
                if (smokeCount > 0) {
                    world.spawnParticle(
                            Particle.LARGE_SMOKE,
                            effectCenter,
                            smokeCount,
                            spread,
                            spread,
                            spread,
                            0.01
                    );
                }

                for (int point = 0; point < ringPoints; point++) {
                    double angle = Math.PI * 2.0 * point / Math.max(1, ringPoints);
                    Location ring = origin.clone().add(
                            Math.cos(angle) * ringRadius,
                            0.15,
                            Math.sin(angle) * ringRadius
                    );
                    world.spawnParticle(Particle.SOUL_FIRE_FLAME, ring, 1, 0.0, 0.0, 0.0, 0.0);
                }

                lived += refresh;
            }
        }.runTaskTimer(host, 0L, refresh);
    }

    private void cleanupShullerVisuals(List<Entity> visuals) {
        for (Entity entity : visuals) {
            if (entity != null && entity.isValid()) {
                entity.remove();
            }
            shullerVisualEntities.remove(entity);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID owner = event.getPlayer().getUniqueId();

        /*
         * Не оставляем наш одноразовый Strength после logout.
         */
        clearShullerStrength(owner, true);

        if (hiddenShullerUntil.containsKey(owner)) {
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                if (!viewer.getUniqueId().equals(owner)) {
                    viewer.showPlayer(host, event.getPlayer());
                }
            }
            hiddenShullerUntil.remove(owner);
        }

        Iterator<Map.Entry<UUID, ActiveMelon>> iterator = melons.entrySet().iterator();
        while (iterator.hasNext()) {
            ActiveMelon active = iterator.next().getValue();
            if (!active.ownerId.equals(owner)) {
                continue;
            }
            if (active.entity != null && active.entity.isValid()) {
                active.entity.remove();
            }
            iterator.remove();
        }

        stopMelonTaskIfEmpty();
    }

    // ============================================================
    // ITEM / CONFIG HELPERS
    // ============================================================

    private boolean isSummer(ItemStack item) {
        return hasMarker(item, summerKey);
    }

    private boolean isShuller(ItemStack item) {
        return hasMarker(item, shullerKey);
    }

    private boolean hasMarker(ItemStack item, NamespacedKey key) {
        if (item == null || item.getType() != Material.PLAYER_HEAD) {
            return false;
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }

        Byte marker = meta.getPersistentDataContainer().get(key, PersistentDataType.BYTE);
        return marker != null && marker.byteValue() == (byte) 1;
    }

    private String formatSummerItem(String text, double damage, double armor) {
        if (text == null) {
            return "";
        }

        return color(
                commonSummer(text)
                        .replace("%damage%", number(damage))
                        .replace("%armor%", number(armor))
                        .replace("%melon_damage%", number(d("summer.ability.melon-rain.damage-hearts", 1.5, 0.0, 20.0)))
                        .replace("%melon_radius%", number(d("summer.ability.melon-rain.radius", 10.0, 1.0, 30.0)))
        );
    }

    private String commonSummer(String text) {
        if (text == null) {
            return "";
        }

        String ability = getConfig().getString("summer.ability.name", "Солнечный Солнцеворот");
        if (ability == null) {
            ability = "Солнечный Солнцеворот";
        }

        return text
                .replace("%ability%", ability)
                .replace("%cooldown%", String.valueOf(i("summer.ability.cooldown-seconds", 300, 1, 86400)));
    }

    private String formatShullerItem(String text, double damage, double speedPercent) {
        if (text == null) {
            return "";
        }

        String ability = getConfig().getString("shuller.ability.name", "Финальный Трюк");
        if (ability == null) {
            ability = "Финальный Трюк";
        }

        return color(
                text.replace("%damage%", number(damage))
                        .replace("%speed%", number(speedPercent))
                        .replace("%ability%", ability)
                        .replace("%cooldown%", String.valueOf(i("shuller.ability.cooldown-seconds", 300, 1, 86400)))
                        .replace("%radius%", number(d("shuller.ability.debuff.radius", 8.0, 0.0, 30.0)))
                        .replace("%invisibility%", number(d("shuller.ability.invisibility-seconds", 3.5, 0.1, 30.0)))
                        .replace("%debuff_duration%", number(d("shuller.ability.debuff.blindness-seconds", 3.0, 0.0, 30.0)))
                        .replace("%strength_level%", String.valueOf(i("shuller.ability.strength-on-hit.level", 5, 1, 20)))
                        .replace("%cloud_radius%", number(d("shuller.ability.cloud.horizontal-radius", 4.5, 0.5, 12.0)))
        );
    }

    private void playSound(World world, Location location, String path, Sound fallback) {
        String configured = getConfig().getString(path + ".sound", fallback.name());
        float volume = (float) d(path + ".volume", 1.0, 0.0, 10.0);
        float pitch = (float) d(path + ".pitch", 1.0, 0.01, 2.0);
        Sound sound = fallback;

        if (configured != null) {
            String name = configured.trim().toUpperCase(Locale.ROOT);

            // Convenient alias: Bukkit 1.16.5 calls the witch laugh CELEBRATE.
            if (name.equals("ENTITY_WITCH_LAUGH")) {
                name = "ENTITY_WITCH_CELEBRATE";
            }

            try {
                sound = Sound.valueOf(name);
            } catch (IllegalArgumentException ignored) {
                getLogger().warning("Unknown sound in config: " + configured + ". Using " + fallback.name());
            }
        }

        world.playSound(location, sound, volume, pitch);
    }

    private Material material(String name, Material fallback) {
        if (name == null) {
            return fallback;
        }
        try {
            return Material.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            getLogger().warning("Unknown material in config: " + name + ". Using " + fallback.name());
            return fallback;
        }
    }

    private int secondsToTicks(double seconds, int minimum, int maximum) {
        long rounded = Math.round(seconds * 20.0);
        if (rounded < minimum) {
            return minimum;
        }
        if (rounded > maximum) {
            return maximum;
        }
        return (int) rounded;
    }

    private int i(String path, int fallback, int minimum, int maximum) {
        int value = getConfig().getInt(path, fallback);
        return Math.max(minimum, Math.min(maximum, value));
    }

    private double d(String path, double fallback, double minimum, double maximum) {
        double value = getConfig().getDouble(path, fallback);
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return fallback;
        }
        return Math.max(minimum, Math.min(maximum, value));
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    private String number(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    // ============================================================
    // CUSTOM HEAD VALUE FOR 1.21.1
    // ============================================================

    private void applyTexture(SkullMeta meta, String value) {
        try {
            PlayerProfile profile = Bukkit.createProfile(UUID.randomUUID(), "SummerBall");
            profile.setProperty(new ProfileProperty("textures", value));
            meta.setPlayerProfile(profile);
        } catch (Exception exception) {
            getLogger().severe(
                    "Could not apply custom head texture: "
                            + exception.getClass().getSimpleName()
                            + ": "
                            + exception.getMessage()
            );
        }
    }

    private static final class PendingStrength {
        private final PotionEffect previousEffect;
        private final long appliedAtMillis;
        private final int timeoutTicks;
        private final UUID token;

        private PendingStrength(
                PotionEffect previousEffect,
                long appliedAtMillis,
                int timeoutTicks,
                UUID token
        ) {
            this.previousEffect = previousEffect;
            this.appliedAtMillis = appliedAtMillis;
            this.timeoutTicks = timeoutTicks;
            this.token = token;
        }
    }

    private static final class ActiveMelon {
        private final FallingBlock entity;
        private final UUID ownerId;
        private final long spawnedAt;
        private BoundingBox previousBox;

        private ActiveMelon(FallingBlock entity, UUID ownerId, long spawnedAt, BoundingBox previousBox) {
            this.entity = entity;
            this.ownerId = ownerId;
            this.spawnedAt = spawnedAt;
            this.previousBox = previousBox;
        }
    }

    private static final class ActiveSlice {
        private final Item entity;
        private final long expiresAt;

        private ActiveSlice(Item entity, long expiresAt) {
            this.entity = entity;
            this.expiresAt = expiresAt;
        }
    }
}
