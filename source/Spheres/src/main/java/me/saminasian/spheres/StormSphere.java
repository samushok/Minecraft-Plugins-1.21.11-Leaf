package me.saminasian.spheres;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class StormSphere extends SphereModule implements Listener {
    private static final String MAIN_TITLE = "STORM • Редактор";
    private static final String ADD_TITLE = "STORM • Добавить";
    private static final String ABILITY_TITLE = "STORM • Способность";

    private final NamespacedKey stormKey;
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Set<UUID> controlledTargets = new HashSet<>();
    private final Set<StormBlackHoleSession> activeBlackHoles = new LinkedHashSet<>();
    private int activeAbilities = 0;

    private static final LinkedHashMap<String, PropertySpec> PROPERTY_SPECS = new LinkedHashMap<>();
    private static final LinkedHashMap<String, AbilitySpec> ABILITY_SPECS = new LinkedHashMap<>();

    static {
        PROPERTY_SPECS.put("speed", new PropertySpec(
                "speed", Material.SUGAR, "Скорость", 15.0, 0.0, 300.0, 5.0,
                Attribute.MOVEMENT_SPEED, AttributeModifier.Operation.MULTIPLY_SCALAR_1, 0.01,
                "&b⚡ Скорость: &f+%value%%"
        ));
        PROPERTY_SPECS.put("attack-speed", new PropertySpec(
                "attack-speed", Material.FEATHER, "Скорость атаки", 10.0, 0.0, 300.0, 5.0,
                Attribute.ATTACK_SPEED, AttributeModifier.Operation.MULTIPLY_SCALAR_1, 0.01,
                "&e⚔ Скорость атаки: &f+%value%%"
        ));
        PROPERTY_SPECS.put("damage", new PropertySpec(
                "damage", Material.IRON_SWORD, "Урон", 4.0, 0.0, 50.0, 1.0,
                Attribute.ATTACK_DAMAGE, AttributeModifier.Operation.ADD_NUMBER, 1.0,
                "&c🗡 Урон: &f+%value%"
        ));
        PROPERTY_SPECS.put("armor", new PropertySpec(
                "armor", Material.IRON_CHESTPLATE, "Броня", 4.0, 0.0, 40.0, 1.0,
                Attribute.ARMOR, AttributeModifier.Operation.ADD_NUMBER, 1.0,
                "&b🛡 Броня: &f+%value%"
        ));
        PROPERTY_SPECS.put("max-health", new PropertySpec(
                "max-health", Material.GOLDEN_APPLE, "Макс. здоровье", 4.0, 0.0, 40.0, 2.0,
                Attribute.MAX_HEALTH, AttributeModifier.Operation.ADD_NUMBER, 1.0,
                "&c❤ Макс. здоровье: &f+%value%"
        ));
        PROPERTY_SPECS.put("knockback-resistance", new PropertySpec(
                "knockback-resistance", Material.SHIELD, "Сопротивление отбрасыванию", 10.0, 0.0, 100.0, 5.0,
                Attribute.KNOCKBACK_RESISTANCE, AttributeModifier.Operation.ADD_NUMBER, 0.01,
                "&9✦ Сопротивление отбрасыванию: &f%value%%"
        ));

        ABILITY_SPECS.put("duration", new AbilitySpec(
                "storm.black-hole.duration-seconds", Material.CLOCK,
                "Длительность (сек.)", 30.0, 5.0, 180.0, 5.0, true
        ));
        ABILITY_SPECS.put("radius", new AbilitySpec(
                "storm.black-hole.radius", Material.COMPASS,
                "Радиус", 14.0, 3.0, 40.0, 1.0, false
        ));
        ABILITY_SPECS.put("core", new AbilitySpec(
                "storm.black-hole.core-radius", Material.ENDER_EYE,
                "Радиус ядра", 2.2, 0.75, 8.0, 0.20, false
        ));
        ABILITY_SPECS.put("pull", new AbilitySpec(
                "storm.black-hole.gravity.pull-strength", Material.MAGMA_CREAM,
                "Сила притяжения", 0.20, 0.0, 2.0, 0.05, false
        ));
        ABILITY_SPECS.put("horizon", new AbilitySpec(
                "storm.black-hole.visuals.block-core.scale", Material.BLACK_CONCRETE,
                "Размер чёрных блоков", 1.45, 0.35, 3.0, 0.10, false
        ));
        ABILITY_SPECS.put("cooldown", new AbilitySpec(
                "storm.black-hole.cooldown-seconds", Material.RECOVERY_COMPASS,
                "Cooldown (сек.)", 90.0, 1.0, 3600.0, 5.0, true
        ));
        ABILITY_SPECS.put("pulse", new AbilitySpec(
                "storm.black-hole.gravity-pulse.interval-seconds", Material.AMETHYST_SHARD,
                "Gravity Pulse (сек.)", 4.0, 1.0, 30.0, 1.0, false
        ));
        ABILITY_SPECS.put("fracture", new AbilitySpec(
                "storm.black-hole.targeting.scan-interval-ticks", Material.SPYGLASS,
                "Скан целей (тики)", 8.0, 2.0, 20.0, 1.0, true
        ));
        ABILITY_SPECS.put("rewind", new AbilitySpec(
                "storm.black-hole.physics.update-interval-ticks", Material.REPEATER,
                "Физика (тики)", 2.0, 1.0, 6.0, 1.0, true
        ));
        ABILITY_SPECS.put("blocks", new AbilitySpec(
                "storm.black-hole.targeting.max-targets", Material.IRON_BARS,
                "Максимум целей", 12.0, 1.0, 32.0, 1.0, true
        ));
        ABILITY_SPECS.put("particles", new AbilitySpec(
                "storm.black-hole.visuals.block-core.spacing", Material.COAL_BLOCK,
                "Расстояние блоков", 1.05, 0.45, 3.0, 0.10, false
        ));
    }

    StormSphere(SpheresPlugin host) {
        super(host);
        this.stormKey = new NamespacedKey(host, "storm_ball");
    }

    @Override public void start() {
        migrateVolumetricVisualDefaults();
        migrateLowTpsDefaults();
        Bukkit.getPluginManager().registerEvents(this, host);
    }

    private void migrateVolumetricVisualDefaults() {
        final String marker = "internal.migrations.storm-volumetric-1-3-2";
        if (getConfig().getBoolean(marker, false)) return;

        boolean changed = false;

        // 1.3.1 shipped the flat BlockDisplay disk enabled by default. Existing
        // servers keep old values when defaults are copied, so disable that
        // legacy layer once during the volumetric upgrade or it would flatten
        // the new spherical silhouette.
        if (getConfig().getBoolean(
                "storm.black-hole.visuals.event-horizon-blocks.enabled",
                false
        )) {
            getConfig().set(
                    "storm.black-hole.visuals.event-horizon-blocks.enabled",
                    false
            );
            changed = true;

            // The old cinematic preset refreshed every tick. The 3D renderer
            // looks smooth at 10 Hz and halves the cosmetic packet/CPU rate.
            if (getConfig().getInt("storm.black-hole.visuals.refresh-ticks", 10) == 1) {
                getConfig().set("storm.black-hole.visuals.refresh-ticks", 2);
                changed = true;
            }
        }

        getConfig().set(marker, true);
        host.saveConfig();

        if (changed) {
            host.getLogger().info(
                    "Migrated STORM visuals to the 1.3.2 volumetric preset."
            );
        }
    }

    private void migrateLowTpsDefaults() {
        final String marker = "internal.migrations.storm-low-tps-1-4-0";
        if (getConfig().getBoolean(marker, false)) return;

        getConfig().set("storm.black-hole.max-concurrent", 1);
        getConfig().set("storm.black-hole.targeting.scan-interval-ticks", 8);
        getConfig().set("storm.black-hole.targeting.max-targets", 12);
        getConfig().set("storm.black-hole.physics.update-interval-ticks", 2);

        getConfig().set("storm.black-hole.time-fracture.enabled", false);
        getConfig().set("storm.black-hole.temporal-echo.enabled", false);
        getConfig().set("storm.black-hole.reality-fractures.enabled", false);

        getConfig().set("storm.black-hole.visuals.mode", "BLOCKS_ONLY");
        getConfig().set("storm.black-hole.visuals.refresh-ticks", 10);
        getConfig().set("storm.black-hole.visuals.particle-density", 0.1);
        getConfig().set("storm.black-hole.visuals.particle-budget-per-refresh", 0);
        getConfig().set("storm.black-hole.visuals.event-horizon-blocks.enabled", false);
        getConfig().set("storm.black-hole.visuals.photon-shell.enabled", false);
        getConfig().set("storm.black-hole.visuals.lensing-halo.enabled", false);
        getConfig().set("storm.black-hole.visuals.infall-streams.enabled", false);
        getConfig().set("storm.black-hole.visuals.gravity-pulse-ring", false);

        getConfig().set("storm.black-hole.collapse.lightning-effect", false);
        getConfig().set("storm.black-hole.collapse.sonic-boom-effect", false);

        getConfig().set(marker, true);
        host.saveConfig();
        host.getLogger().info(
                "Migrated STORM to the 1.4.0 block-only low-TPS preset."
        );
    }

    @Override public void stop() {
        for (StormBlackHoleSession session : new ArrayList<>(activeBlackHoles)) {
            session.shutdown(false);
        }
        activeBlackHoles.clear();
        cooldowns.clear();
        controlledTargets.clear();
        activeAbilities = 0;
    }

    ItemStack createStormBall() {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        if (!(item.getItemMeta() instanceof SkullMeta meta)) return item;

        meta.setDisplayName(color(formatCommon(getConfig().getString("storm.item.name", "&b&lШАР &f⚡ &bSTORM"))));
        meta.getPersistentDataContainer().set(stormKey, PersistentDataType.BYTE, (byte)1);

        for (PropertyEntry entry : activeProperties()) {
            PropertySpec spec = PROPERTY_SPECS.get(entry.id());
            if (spec == null) continue;
            double amount = entry.value() * spec.multiplier();
            meta.addAttributeModifier(spec.attribute(), new AttributeModifier(
                    new NamespacedKey(host, "storm_" + entry.id().replace('-', '_')),
                    amount,
                    spec.operation(),
                    EquipmentSlotGroup.OFFHAND
            ));
        }

        if (getConfig().getBoolean("storm.item.hide-vanilla-attributes", true)) {
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        }

        List<String> lore = new ArrayList<>();
        List<String> description = getConfig().getStringList("storm.item.description");
        if (description.isEmpty()) {
            description = List.of(
                    "&7Сфера, в которой заключена настоящая чёрная дыра.",
                    "",
                    "%bonuses%",
                    "",
                    "&7SHIFT — &b%ability%",
                    "%ability_details%"
            );
        }
        List<String> bonuses = generatedBonusLore();
        List<String> abilityDetails = generatedAbilityLore();
        for (String raw : description) {
            if ("%bonuses%".equals(raw.trim())) {
                lore.addAll(bonuses);
            } else if ("%ability_details%".equals(raw.trim())) {
                lore.addAll(abilityDetails);
            } else {
                lore.add(color(formatCommon(raw)));
            }
        }
        meta.setLore(lore);

        String texture = getConfig().getString("storm.item.texture", "");
        if (texture == null || texture.isBlank()) {
            // Backwards compatibility with the first STORM config.
            texture = getConfig().getString("storm.item.texture-value", "");
        }
        if (texture != null && !texture.isBlank()) applyTexture(meta, texture.trim());

        item.setItemMeta(meta);
        return item;
    }

    boolean isStorm(ItemStack item) {
        if (item == null || item.getType() != Material.PLAYER_HEAD) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        Byte marker = meta.getPersistentDataContainer().get(stormKey, PersistentDataType.BYTE);
        return marker != null && marker == (byte)1;
    }

    void openEditor(Player player) {
        refreshOnlineStormItems();
        openMain(player);
    }

    void refreshOnlineStormItems() {
        for (Player player : Bukkit.getOnlinePlayers()) refreshPlayerStormItems(player);
    }

    private void refreshPlayerStormItems(Player player) {
        PlayerInventory inv = player.getInventory();
        for (int slot = 0; slot < inv.getSize(); slot++) {
            ItemStack old = inv.getItem(slot);
            if (isStorm(old)) {
                ItemStack fresh = createStormBall();
                fresh.setAmount(old.getAmount());
                inv.setItem(slot, fresh);
            }
        }
        if (isStorm(inv.getItemInOffHand())) inv.setItemInOffHand(createStormBall());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(host, () -> {
            if (event.getPlayer().isOnline()) refreshPlayerStormItems(event.getPlayer());
        }, 2L);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSneak(PlayerToggleSneakEvent event) {
        if (!event.isSneaking()) return;
        Player player = event.getPlayer();
        if (isStorm(player.getInventory().getItemInOffHand())) activateStorm(player);
    }

    private void activateStorm(Player owner) {
        int maxConcurrent = Math.max(
                0,
                getConfig().getInt("storm.black-hole.max-concurrent", 1)
        );
        if (maxConcurrent > 0 && activeAbilities >= maxConcurrent) {
            owner.sendMessage(color(getConfig().getString(
                    "storm.messages.busy",
                    "&5STORM &7не может создать ещё одну сингулярность прямо сейчас."
            )));
            return;
        }

        if (getConfig().getBoolean("storm.black-hole.lifecycle.one-active-per-owner", true)
                && hasActiveBlackHole(owner.getUniqueId())) {
            owner.sendMessage(color(getConfig().getString(
                    "storm.messages.owner-active",
                    "&5STORM &7у тебя уже есть активная сингулярность."
            )));
            return;
        }

        long now = System.currentTimeMillis();
        long cooldownMillis = clampInt(
                getConfig().getInt("storm.black-hole.cooldown-seconds", 90),
                1,
                86400
        ) * 1000L;
        Long previous = cooldowns.get(owner.getUniqueId());
        if (previous != null && now - previous < cooldownMillis) {
            long remaining = Math.max(
                    1L,
                    (cooldownMillis - (now - previous) + 999L) / 1000L
            );
            owner.sendMessage(color(getConfig().getString(
                    "storm.messages.cooldown",
                    "&5STORM &7снова создаст сингулярность через &f%time% сек."
            ).replace("%time%", String.valueOf(remaining))));
            return;
        }

        Location center = blackHoleCenter(owner);
        double radius = d("storm.black-hole.radius", 14.0, 3.0, 40.0);

        if (getConfig().getBoolean("storm.black-hole.targeting.require-target", false)
                && !hasPotentialTarget(owner, center, radius)) {
            owner.sendMessage(color(getConfig().getString(
                    "storm.messages.no-target",
                    "&7Рядом нет целей для &5Сингулярности&7."
            )));
            return;
        }

        StormBlackHoleSession session = new StormBlackHoleSession(this, owner, center);
        activeBlackHoles.add(session);
        activeAbilities++;
        cooldowns.put(owner.getUniqueId(), now);

        try {
            session.startSession();
            owner.sendMessage(color(getConfig().getString(
                    "storm.messages.created",
                    "&5&lSTORM &8» &fСингулярность создана."
            )));
        } catch (RuntimeException error) {
            // A failed start must not consume cooldown or leave the owner/session
            // locked. shutdown(false) also removes any partially spawned displays.
            session.shutdown(false);
            cooldowns.remove(owner.getUniqueId());
            host.getLogger().log(
                    java.util.logging.Level.SEVERE,
                    "Could not start STORM Black Hole for " + owner.getName(),
                    error
            );
            owner.sendMessage(color(getConfig().getString(
                    "storm.messages.failed",
                    "&cSTORM не смог создать сингулярность. Кулдаун не потрачен."
            )));
        }
    }

    private Location blackHoleCenter(Player owner) {
        Location base = owner.getLocation().clone();
        Vector direction = base.getDirection().setY(0.0);
        if (direction.lengthSquared() < 0.0001) {
            direction = new Vector(0.0, 0.0, 1.0);
        } else {
            direction.normalize();
        }

        double forward = d("storm.black-hole.spawn.forward-offset", 4.0, 0.0, 16.0);
        double height = d("storm.black-hole.spawn.height-offset", 1.6, -4.0, 16.0);
        Location center = base.add(direction.multiply(forward)).add(0.0, height, 0.0);

        double minY = owner.getWorld().getMinHeight() + 1.0;
        double maxY = owner.getWorld().getMaxHeight() - 2.0;
        center.setY(Math.max(minY, Math.min(maxY, center.getY())));
        return center;
    }

    private boolean hasPotentialTarget(Player owner, Location center, double radius) {
        boolean players = getConfig().getBoolean("storm.black-hole.targeting.players", true);
        boolean mobs = getConfig().getBoolean("storm.black-hole.targeting.mobs", false);
        boolean requirePvp = getConfig().getBoolean("storm.black-hole.targeting.require-pvp", true);
        boolean excludeOwner = getConfig().getBoolean("storm.black-hole.targeting.exclude-owner", true);

        for (Entity entity : owner.getWorld().getNearbyEntities(center, radius, radius, radius)) {
            if (!(entity instanceof LivingEntity target)
                    || !target.isValid()
                    || target.isDead()
                    || target.isInvulnerable()
                    || controlledTargets.contains(target.getUniqueId())) {
                continue;
            }

            if (excludeOwner && target.getUniqueId().equals(owner.getUniqueId())) continue;

            if (target instanceof Player player) {
                if (!players
                        || player.getGameMode() == GameMode.CREATIVE
                        || player.getGameMode() == GameMode.SPECTATOR
                        || (requirePvp && !owner.getWorld().getPVP())) {
                    continue;
                }
                return true;
            }

            if (mobs) return true;
        }
        return false;
    }

    private boolean hasActiveBlackHole(UUID ownerId) {
        for (StormBlackHoleSession session : activeBlackHoles) {
            if (session.ownerId().equals(ownerId)) return true;
        }
        return false;
    }

    int stopAllBlackHoles(boolean collapse) {
        int count = activeBlackHoles.size();
        for (StormBlackHoleSession session : new ArrayList<>(activeBlackHoles)) {
            session.shutdown(collapse);
        }
        return count;
    }

    int activeBlackHoleCount() {
        return activeBlackHoles.size();
    }

    boolean tryLockTarget(UUID targetId) {
        return controlledTargets.add(targetId);
    }

    void releaseTarget(UUID targetId) {
        controlledTargets.remove(targetId);
    }

    void onBlackHoleFinished(StormBlackHoleSession session) {
        activeBlackHoles.remove(session);
        activeAbilities = Math.max(0, activeAbilities - 1);
    }

    // ========================= GUI =========================

    private void openMain(Player player) {
        Inventory inv = Bukkit.createInventory(null, 54, MAIN_TITLE);
        fill(inv, Material.BLACK_STAINED_GLASS_PANE);

        int[] slots = {10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34};
        List<PropertyEntry> entries = activeProperties();
        for (int n = 0; n < entries.size() && n < slots.length; n++) {
            PropertyEntry entry = entries.get(n);
            PropertySpec spec = PROPERTY_SPECS.get(entry.id());
            if (spec == null) continue;
            ItemStack icon = named(spec.icon(), "&b" + spec.name(), List.of(
                    "&7Значение: &f" + number(entry.value()),
                    valueBar(entry.value(), spec.min(), spec.max()),
                    "&7Строка lore: " + color(entry.display()),
                    "",
                    "&aЛКМ &7+ " + number(spec.step()),
                    "&cПКМ &7- " + number(spec.step()),
                    "&eShift+ЛКМ &7поднять выше",
                    "&eShift+ПКМ &7опустить ниже",
                    "&4Q &7удалить свойство"
            ));
            ItemMeta meta = icon.getItemMeta();
            meta.getPersistentDataContainer().set(new NamespacedKey(host, "storm_gui_property"), PersistentDataType.STRING, entry.id());
            icon.setItemMeta(meta);
            inv.setItem(slots[n], icon);
        }

        inv.setItem(45, named(Material.LIME_DYE, "&a➕ Добавить свойство", List.of("&7Выбрать новый бонус для шара.")));
        inv.setItem(46, named(Material.END_PORTAL_FRAME, "&5⚫ Настройки Чёрной Дыры", List.of(
                "&7Длительность, радиус, гравитация,",
                "&7Time Fracture, 3D-ядро, всасывание и частицы."
        )));
        ItemStack previewIcon = createStormBall();
        ItemMeta previewMeta = previewIcon.getItemMeta();
        if (previewMeta != null) {
            List<String> previewLore = previewMeta.hasLore() && previewMeta.getLore() != null
                    ? new ArrayList<>(previewMeta.getLore())
                    : new ArrayList<>();
            previewLore.add("");
            previewLore.add(color("&aЛКМ &7— выдать preview"));
            previewMeta.setLore(previewLore);
            previewIcon.setItemMeta(previewMeta);
        }
        inv.setItem(48, previewIcon);
        inv.setItem(49, named(Material.BOOK, "&eУправление", List.of(
                "&7Свойства полностью data-driven.",
                "&7Удалил Damage — исчез и эффект, и lore.",
                "&7Добавил Speed — появился бонус и строка."
        )));
        inv.setItem(53, named(Material.BARRIER, "&cЗакрыть", List.of()));
        player.openInventory(inv);
    }

    private void openAdd(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, ADD_TITLE);
        fill(inv, Material.GRAY_STAINED_GLASS_PANE);
        int slot = 9;
        Set<String> active = new HashSet<>();
        for (PropertyEntry e : activeProperties()) active.add(e.id());
        for (PropertySpec spec : PROPERTY_SPECS.values()) {
            if (active.contains(spec.id())) continue;
            ItemStack icon = named(spec.icon(), "&a➕ " + spec.name(), List.of(
                    "&7Стартовое значение: &f" + number(spec.defaultValue()),
                    "&7Нажмите, чтобы добавить."
            ));
            ItemMeta meta = icon.getItemMeta();
            meta.getPersistentDataContainer().set(new NamespacedKey(host, "storm_gui_add"), PersistentDataType.STRING, spec.id());
            icon.setItemMeta(meta);
            inv.setItem(slot++, icon);
        }
        inv.setItem(26, named(Material.ARROW, "&e← Назад", List.of()));
        player.openInventory(inv);
    }

    private void openAbility(Player player) {
        Inventory inv = Bukkit.createInventory(null, 45, ABILITY_TITLE);
        fill(inv, Material.BLUE_STAINED_GLASS_PANE);
        int slot = 10;
        for (AbilitySpec spec : ABILITY_SPECS.values()) {
            double value = getConfig().getDouble(spec.path(), spec.defaultValue());
            ItemStack icon = named(spec.icon(), "&b" + spec.name(), List.of(
                    "&7Сейчас: &f" + number(value),
                    valueBar(value, spec.min(), spec.max()),
                    "",
                    "&aЛКМ &7+ " + number(spec.step()),
                    "&cПКМ &7- " + number(spec.step()),
                    "&eShift &7= шаг x5"
            ));
            ItemMeta meta = icon.getItemMeta();
            meta.getPersistentDataContainer().set(new NamespacedKey(host, "storm_gui_ability"), PersistentDataType.STRING, spec.path());
            icon.setItemMeta(meta);
            inv.setItem(slot++, icon);
            if (slot == 17) slot = 19;
        }
        inv.setItem(27, toggleIcon(
                "storm.black-hole.blindness.enabled",
                Material.ENDER_EYE,
                "&5Blindness"
        ));
        inv.setItem(28, toggleIcon(
                "storm.black-hole.gravity-pulse.enabled",
                Material.AMETHYST_BLOCK,
                "&dGravity Pulse"
        ));
        inv.setItem(29, toggleIcon(
                "storm.black-hole.presentation.capture-title-enabled",
                Material.NAME_TAG,
                "&dCapture Title"
        ));
        inv.setItem(30, toggleIcon(
                "storm.black-hole.presentation.owner-title-enabled",
                Material.BOOK,
                "&bOwner Title"
        ));
        inv.setItem(31, toggleIcon(
                "storm.black-hole.collapse.countdown-title-enabled",
                Material.CLOCK,
                "&5Collapse Countdown"
        ));
        inv.setItem(32, toggleIcon(
                "storm.black-hole.lifecycle.cancel-if-owner-unavailable",
                Material.BARRIER,
                "&8Убирать при выходе владельца"
        ));
        inv.setItem(33, toggleIcon(
                "storm.black-hole.collapse.enabled",
                Material.END_CRYSTAL,
                "&fCollapse Finale"
        ));
        inv.setItem(34, toggleIcon(
                "storm.black-hole.collapse.damage-enabled",
                Material.NETHERITE_SWORD,
                "&cCollapse Damage"
        ));
        inv.setItem(44, named(Material.ARROW, "&e← Назад", List.of()));
        player.openInventory(inv);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEditorClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        String title = event.getView().getTitle();
        if (!title.equals(MAIN_TITLE) && !title.equals(ADD_TITLE) && !title.equals(ABILITY_TITLE)) return;
        if (!player.hasPermission("stormconfig.use")) {
            event.setCancelled(true);
            player.closeInventory();
            return;
        }
        event.setCancelled(true);
        ItemStack item = event.getCurrentItem();
        if (item == null || item.getType().isAir()) return;

        if (title.equals(MAIN_TITLE)) {
            if (event.getRawSlot() == 45) { openAdd(player); return; }
            if (event.getRawSlot() == 46) { openAbility(player); return; }
            if (event.getRawSlot() == 48) {
                ItemStack preview = createStormBall();
                Map<Integer, ItemStack> rest = player.getInventory().addItem(preview);
                for (ItemStack left : rest.values()) player.getWorld().dropItemNaturally(player.getLocation(), left);
                player.sendMessage(color("&aSTORM preview выдан."));
                return;
            }
            if (event.getRawSlot() == 53) { player.closeInventory(); return; }

            ItemMeta meta = item.getItemMeta();
            if (meta == null) return;
            String id = meta.getPersistentDataContainer().get(new NamespacedKey(host, "storm_gui_property"), PersistentDataType.STRING);
            if (id == null) return;
            editProperty(player, id, event.getClick());
            return;
        }

        if (title.equals(ADD_TITLE)) {
            if (event.getRawSlot() == 26) { openMain(player); return; }
            ItemMeta meta = item.getItemMeta();
            if (meta == null) return;
            String id = meta.getPersistentDataContainer().get(new NamespacedKey(host, "storm_gui_add"), PersistentDataType.STRING);
            if (id != null) {
                addProperty(id);
                saveRefresh();
                openMain(player);
            }
            return;
        }

        if (title.equals(ABILITY_TITLE)) {
            if (event.getRawSlot() == 44) { openMain(player); return; }

            String togglePath = switch (event.getRawSlot()) {
                case 27 -> "storm.black-hole.blindness.enabled";
                case 28 -> "storm.black-hole.gravity-pulse.enabled";
                case 29 -> "storm.black-hole.presentation.capture-title-enabled";
                case 30 -> "storm.black-hole.presentation.owner-title-enabled";
                case 31 -> "storm.black-hole.collapse.countdown-title-enabled";
                case 32 -> "storm.black-hole.lifecycle.cancel-if-owner-unavailable";
                case 33 -> "storm.black-hole.collapse.enabled";
                case 34 -> "storm.black-hole.collapse.damage-enabled";
                default -> null;
            };
            if (togglePath != null) {
                getConfig().set(togglePath, !getConfig().getBoolean(togglePath, true));
                saveRefresh();
                openAbility(player);
                return;
            }

            ItemMeta meta = item.getItemMeta();
            if (meta == null) return;
            String path = meta.getPersistentDataContainer().get(new NamespacedKey(host, "storm_gui_ability"), PersistentDataType.STRING);
            if (path != null) {
                editAbility(path, event.getClick());
                saveRefresh();
                openAbility(player);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEditorDrag(InventoryDragEvent event) {
        String title = event.getView().getTitle();
        if (!title.equals(MAIN_TITLE) && !title.equals(ADD_TITLE) && !title.equals(ABILITY_TITLE)) return;
        if (!(event.getWhoClicked() instanceof Player player) || !player.hasPermission("stormconfig.use")) {
            event.setCancelled(true);
            return;
        }
        event.setCancelled(true);
    }

    private ItemStack toggleIcon(String path, Material material, String label) {
        boolean enabled = getConfig().getBoolean(path, true);
        return named(
                enabled ? material : Material.GRAY_DYE,
                (enabled ? "&a✔ " : "&c✘ ") + label,
                List.of(
                        enabled ? "&aВключено" : "&cВыключено",
                        "&7ЛКМ — переключить"
                )
        );
    }

    private void editProperty(Player player, String id, ClickType click) {
        PropertySpec spec = PROPERTY_SPECS.get(id);
        if (spec == null) return;
        String base = "storm.item.properties." + id;
        if (click == ClickType.DROP || click == ClickType.CONTROL_DROP) {
            // Do not physically delete the default section: Spheres intentionally copies
            // newly introduced defaults on startup, so a deleted default property could
            // otherwise reappear after restart. A persisted false is restart-safe.
            getConfig().set(base + ".enabled", false);
            saveRefresh();
            player.sendMessage(color("&cОтключено свойство: &f" + spec.name()));
            openMain(player);
            return;
        }
        int direction = click.isShiftClick() ? (click.isLeftClick() ? -1 : 1) : 0;
        if (direction != 0) {
            moveProperty(id, direction);
            saveRefresh();
            openMain(player);
            return;
        }
        double value = getConfig().getDouble(base + ".value", spec.defaultValue());
        if (click.isLeftClick()) value += spec.step();
        else if (click.isRightClick()) value -= spec.step();
        value = Math.max(spec.min(), Math.min(spec.max(), value));
        getConfig().set(base + ".value", rounded(value));
        saveRefresh();
        openMain(player);
    }

    private void moveProperty(String id, int direction) {
        List<PropertyEntry> entries = activeProperties();
        int index = -1;
        for (int n = 0; n < entries.size(); n++) {
            if (entries.get(n).id().equals(id)) {
                index = n;
                break;
            }
        }
        int otherIndex = index + direction;
        if (index < 0 || otherIndex < 0 || otherIndex >= entries.size()) return;

        PropertyEntry current = entries.get(index);
        PropertyEntry other = entries.get(otherIndex);
        getConfig().set("storm.item.properties." + current.id() + ".order", other.order());
        getConfig().set("storm.item.properties." + other.id() + ".order", current.order());
    }

    private String valueBar(double value, double min, double max) {
        double progress = max <= min ? 1.0 : Math.max(0.0, Math.min(1.0, (value - min) / (max - min)));
        int filled = (int)Math.round(progress * 10.0);
        StringBuilder out = new StringBuilder("&8[&b");
        for (int i = 0; i < 10; i++) {
            if (i == filled) out.append("&7");
            out.append("■");
        }
        return out.append("&8]").toString();
    }

    private void addProperty(String id) {
        PropertySpec spec = PROPERTY_SPECS.get(id);
        if (spec == null) return;
        String base = "storm.item.properties." + id;

        // Re-enabling a previously removed property keeps the owner's old tuning.
        // Defaults are only written for a property that has never existed.
        if (!getConfig().contains(base + ".value")) {
            getConfig().set(base + ".value", spec.defaultValue());
        }
        if (!getConfig().contains(base + ".display")) {
            getConfig().set(base + ".display", spec.defaultDisplay());
        }
        if (!getConfig().contains(base + ".order")) {
            int maxOrder = activeProperties().stream().mapToInt(PropertyEntry::order).max().orElse(0);
            getConfig().set(base + ".order", maxOrder + 10);
        }
        getConfig().set(base + ".enabled", true);
    }

    private void editAbility(String path, ClickType click) {
        AbilitySpec spec = ABILITY_SPECS.values().stream().filter(s -> s.path().equals(path)).findFirst().orElse(null);
        if (spec == null) return;
        double value = getConfig().getDouble(path, spec.defaultValue());
        double step = spec.step() * (click.isShiftClick() ? 5.0 : 1.0);
        if (click.isLeftClick()) value += step;
        else if (click.isRightClick()) value -= step;
        value = Math.max(spec.min(), Math.min(spec.max(), value));
        getConfig().set(path, spec.integer() ? (int)Math.round(value) : rounded(value));
    }

    private void saveRefresh() {
        host.saveConfig();
        host.reloadConfig();
        refreshOnlineStormItems();
    }

    private List<PropertyEntry> activeProperties() {
        ConfigurationSection root = getConfig().getConfigurationSection("storm.item.properties");
        if (root == null) return new ArrayList<>();
        List<PropertyEntry> entries = new ArrayList<>();
        for (String id : root.getKeys(false)) {
            PropertySpec spec = PROPERTY_SPECS.get(id);
            if (spec == null) continue;
            String path = "storm.item.properties." + id;
            if (!getConfig().getBoolean(path + ".enabled", true)) continue;
            double rawValue = getConfig().getDouble(path + ".value", spec.defaultValue());
            double safeValue = Math.max(spec.min(), Math.min(spec.max(), rawValue));
            entries.add(new PropertyEntry(
                    id,
                    safeValue,
                    getConfig().getString(path + ".display", spec.defaultDisplay()),
                    getConfig().getInt(path + ".order", 10)
            ));
        }
        entries.sort(Comparator.comparingInt(PropertyEntry::order).thenComparing(PropertyEntry::id));
        return entries;
    }

    private List<String> generatedBonusLore() {
        List<String> lines = new ArrayList<>();
        for (PropertyEntry entry : activeProperties()) {
            String display = entry.display() == null ? PROPERTY_SPECS.get(entry.id()).defaultDisplay() : entry.display();
            lines.add(color(display.replace("%value%", number(entry.value()))));
        }
        if (lines.isEmpty()) lines.add(color(getConfig().getString("storm.item.no-bonuses-line", "&8Нет пассивных бонусов")));
        return lines;
    }

    private List<String> generatedAbilityLore() {
        List<String> lines = new ArrayList<>();
        lines.add(color("&5⚫ Чёрная дыра: &f"
                + number(d("storm.black-hole.duration-seconds", 30.0, 2.0, 180.0))
                + " сек."));
        lines.add(color("&7🌌 Радиус: &f"
                + number(d("storm.black-hole.radius", 14.0, 3.0, 40.0))
                + " блоков"));
        lines.add(color("&7🌀 Притяжение: &f"
                + number(d("storm.black-hole.gravity.pull-strength", 0.20, 0.0, 2.0))));
        if (getConfig().getBoolean("storm.black-hole.blindness.enabled", true)) {
            lines.add(color("&8◉ &7Blindness внутри сингулярности"));
        }
        if (getConfig().getBoolean("storm.black-hole.time-fracture.enabled", false)) {
            lines.add(color("&d⌛ Time Fracture: &fкаждые "
                    + number(d("storm.black-hole.time-fracture.interval-seconds", 6.0, 1.0, 40.0))
                    + " сек."));
        }
        if (getConfig().getBoolean("storm.black-hole.reality-fractures.enabled", false)) {
            lines.add(color("&5✦ Reality Fractures: &fON"));
        }
        lines.add(color("&7⏱ Перезарядка: &f"
                + i("storm.black-hole.cooldown-seconds", 90, 1, 86400)
                + " сек."));
        return lines;
    }

    private String formatCommon(String raw) {
        if (raw == null) return "";
        return raw
                .replace("%ability%", getConfig().getString(
                        "storm.black-hole.name",
                        "Сингулярность"
                ))
                .replace("%duration%", number(d(
                        "storm.black-hole.duration-seconds", 30.0, 2.0, 180.0
                )))
                .replace("%radius%", number(d(
                        "storm.black-hole.radius", 14.0, 3.0, 40.0
                )))
                .replace("%cooldown%", String.valueOf(i(
                        "storm.black-hole.cooldown-seconds", 90, 1, 86400
                )));
    }

    private void applyTexture(SkullMeta meta, String input) {
        try {
            String value = normalizeTextureValue(input);
            if (value == null || value.isBlank()) return;

            PlayerProfile profile = Bukkit.createProfile(UUID.randomUUID(), "StormBall");
            profile.setProperty(new ProfileProperty("textures", value));
            meta.setPlayerProfile(profile);
        } catch (Exception e) {
            getLogger().warning("Could not apply STORM texture: " + e.getMessage());
        }
    }

    private String normalizeTextureValue(String input) {
        if (input == null) return "";
        String raw = input.trim();
        if (raw.isEmpty()) return "";

        // Ready-made Mojang/MineSkin base64 texture value.
        if (raw.startsWith("eyJ") || raw.startsWith("ew")) {
            return raw;
        }

        // Allow pasting only the textures.minecraft.net hash.
        if (raw.matches("[A-Fa-f0-9]{24,160}")) {
            raw = "https://textures.minecraft.net/texture/" + raw;
        } else if (raw.startsWith("textures.minecraft.net/texture/")) {
            raw = "https://" + raw;
        } else if (raw.startsWith("http://textures.minecraft.net/texture/")) {
            raw = "https://" + raw.substring("http://".length());
        }

        // Custom skull textures are expected to use the Mojang texture CDN.
        if (raw.startsWith("https://textures.minecraft.net/texture/")) {
            String safeUrl = raw.replace("\\", "").replace("\"", "");
            String json = "{\"textures\":{\"SKIN\":{\"url\":\"" + safeUrl + "\"}}}";
            return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        }

        getLogger().warning(
                "storm.item.texture must be base64, a textures.minecraft.net URL, or its texture hash. Texture ignored."
        );
        return "";
    }

    private ItemStack named(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(color(name));
        List<String> colored = new ArrayList<>();
        for (String line : lore) colored.add(color(line));
        meta.setLore(colored);
        item.setItemMeta(meta);
        return item;
    }

    private void fill(Inventory inv, Material material) {
        ItemStack pane = named(material, " ", List.of());
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, pane);
    }

    private int i(String path, int fallback, int min, int max) {
        return clampInt(getConfig().getInt(path, fallback), min, max);
    }

    private double d(String path, double fallback, double min, double max) {
        double value = getConfig().getDouble(path, fallback);
        if (!Double.isFinite(value)) return fallback;
        return Math.max(min, Math.min(max, value));
    }

    private int clampInt(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
    private double rounded(double value) { return Math.round(value * 1000.0) / 1000.0; }
    private String color(String raw) { return ChatColor.translateAlternateColorCodes('&', raw == null ? "" : raw); }
    private String number(double value) { return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString(); }

    private record PropertySpec(
            String id, Material icon, String name, double defaultValue, double min, double max, double step,
            Attribute attribute, AttributeModifier.Operation operation, double multiplier, String defaultDisplay
    ) {}

    private record PropertyEntry(String id, double value, String display, int order) {}

    private record AbilitySpec(
            String path, Material icon, String name, double defaultValue, double min, double max, double step, boolean integer
    ) {}
}
