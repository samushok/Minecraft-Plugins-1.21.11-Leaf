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
import java.util.*;

final class StormSphere extends SphereModule implements Listener {
    private static final String MAIN_TITLE = "STORM • Редактор";
    private static final String ADD_TITLE = "STORM • Добавить";
    private static final String ABILITY_TITLE = "STORM • Способность";

    private final NamespacedKey stormKey;
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private int activeAbilities = 0;

    private static final LinkedHashMap<String, PropertySpec> PROPERTY_SPECS = new LinkedHashMap<>();
    private static final LinkedHashMap<String, AbilitySpec> ABILITY_SPECS = new LinkedHashMap<>();

    static {
        PROPERTY_SPECS.put("speed", new PropertySpec(
                "speed", Material.SUGAR, "Скорость", 15.0, -95.0, 300.0, 5.0,
                Attribute.MOVEMENT_SPEED, AttributeModifier.Operation.MULTIPLY_SCALAR_1, 0.01,
                "&b⚡ Скорость: &f+%value%%"
        ));
        PROPERTY_SPECS.put("attack-speed", new PropertySpec(
                "attack-speed", Material.FEATHER, "Скорость атаки", 10.0, -90.0, 300.0, 5.0,
                Attribute.ATTACK_SPEED, AttributeModifier.Operation.MULTIPLY_SCALAR_1, 0.01,
                "&e⚔ Скорость атаки: &f+%value%%"
        ));
        PROPERTY_SPECS.put("damage", new PropertySpec(
                "damage", Material.IRON_SWORD, "Урон", 4.0, -20.0, 50.0, 1.0,
                Attribute.ATTACK_DAMAGE, AttributeModifier.Operation.ADD_NUMBER, 1.0,
                "&c🗡 Урон: &f+%value%"
        ));
        PROPERTY_SPECS.put("armor", new PropertySpec(
                "armor", Material.IRON_CHESTPLATE, "Броня", 4.0, -20.0, 40.0, 1.0,
                Attribute.ARMOR, AttributeModifier.Operation.ADD_NUMBER, 1.0,
                "&b🛡 Броня: &f+%value%"
        ));
        PROPERTY_SPECS.put("max-health", new PropertySpec(
                "max-health", Material.GOLDEN_APPLE, "Макс. здоровье", 4.0, -18.0, 40.0, 2.0,
                Attribute.MAX_HEALTH, AttributeModifier.Operation.ADD_NUMBER, 1.0,
                "&c❤ Макс. здоровье: &f+%value%"
        ));
        PROPERTY_SPECS.put("knockback-resistance", new PropertySpec(
                "knockback-resistance", Material.SHIELD, "Сопротивление отбрасыванию", 10.0, 0.0, 100.0, 5.0,
                Attribute.KNOCKBACK_RESISTANCE, AttributeModifier.Operation.ADD_NUMBER, 0.01,
                "&9✦ Сопротивление отбрасыванию: &f%value%%"
        ));

        ABILITY_SPECS.put("radius", new AbilitySpec("storm.ability.radius", Material.COMPASS, "Радиус", 7.0, 2.0, 24.0, 1.0, false));
        ABILITY_SPECS.put("launch", new AbilitySpec("storm.ability.launch-power", Material.FIREWORK_ROCKET, "Подброс", 1.35, 0.2, 3.5, 0.10, false));
        ABILITY_SPECS.put("hold", new AbilitySpec("storm.ability.hold-ticks", Material.CLOCK, "Зависание (тики)", 8.0, 0.0, 40.0, 1.0, true));
        ABILITY_SPECS.put("slam", new AbilitySpec("storm.ability.slam-power", Material.ANVIL, "Сила падения", 2.8, 0.5, 5.0, 0.10, false));
        ABILITY_SPECS.put("damage", new AbilitySpec("storm.ability.damage-hearts", Material.NETHERITE_SWORD, "Урон slam (сердца)", 3.0, 0.0, 20.0, 0.5, false));
        ABILITY_SPECS.put("cooldown", new AbilitySpec("storm.ability.cooldown-seconds", Material.RECOVERY_COMPASS, "Cooldown (сек.)", 60.0, 1.0, 3600.0, 5.0, true));
        ABILITY_SPECS.put("particles", new AbilitySpec("storm.visual.particle-density", Material.END_CRYSTAL, "Плотность частиц", 1.0, 0.1, 2.0, 0.1, false));
    }

    StormSphere(SpheresPlugin host) {
        super(host);
        this.stormKey = new NamespacedKey(host, "storm_ball");
    }

    @Override public void start() {
        Bukkit.getPluginManager().registerEvents(this, host);
    }

    @Override public void stop() {
        cooldowns.clear();
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
                    "&7Сфера, в которой запечатана сила грозового фронта.",
                    "",
                    "%bonuses%",
                    "",
                    "&7SHIFT — &b%ability%",
                    "&7Радиус: &f%radius% &7| Cooldown: &f%cooldown% сек."
            );
        }
        List<String> bonuses = generatedBonusLore();
        for (String raw : description) {
            if ("%bonuses%".equals(raw.trim())) {
                lore.addAll(bonuses);
            } else {
                lore.add(color(formatCommon(raw)));
            }
        }
        meta.setLore(lore);

        String texture = getConfig().getString("storm.item.texture-value", "");
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
        int maxConcurrent = clampInt(getConfig().getInt("storm.performance.max-concurrent-abilities", 3), 1, 8);
        if (activeAbilities >= maxConcurrent) {
            owner.sendMessage(color(getConfig().getString("storm.messages.busy", "&eСлишком много штормов одновременно.")));
            return;
        }

        long now = System.currentTimeMillis();
        long cooldownMillis = clampInt(getConfig().getInt("storm.ability.cooldown-seconds", 60), 1, 86400) * 1000L;
        Long previous = cooldowns.get(owner.getUniqueId());
        if (previous != null && now - previous < cooldownMillis) {
            long remaining = Math.max(1L, (cooldownMillis - (now - previous) + 999L) / 1000L);
            owner.sendMessage(color(getConfig().getString(
                    "storm.messages.cooldown",
                    "&bSTORM &7будет доступен через &f%time% сек."
            ).replace("%time%", String.valueOf(remaining))));
            return;
        }

        double radius = d("storm.ability.radius", 7.0, 2.0, 24.0);
        List<LivingEntity> targets = collectTargets(owner, radius);
        if (targets.isEmpty() && getConfig().getBoolean("storm.ability.require-target", true)) {
            owner.sendMessage(color(getConfig().getString("storm.messages.no-target", "&7Рядом нет целей для STORM.")));
            return;
        }

        cooldowns.put(owner.getUniqueId(), now);
        activeAbilities++;
        Location center = owner.getLocation().clone();
        World world = owner.getWorld();

        playSound(world, center, "storm.sounds.start", Sound.ENTITY_LIGHTNING_BOLT_THUNDER);
        renderWindup(owner, center, targets);

        int windup = i("storm.ability.windup-ticks", 16, 4, 60);
        Bukkit.getScheduler().runTaskLater(host, () -> {
            if (!owner.isOnline()) {
                activeAbilities = Math.max(0, activeAbilities - 1);
                return;
            }
            launchTargets(owner, targets);
        }, windup);
    }

    private List<LivingEntity> collectTargets(Player owner, double radius) {
        boolean affectMobs = getConfig().getBoolean("storm.ability.affect-mobs", false);
        double r2 = radius * radius;
        List<LivingEntity> result = new ArrayList<>();
        for (Entity entity : owner.getNearbyEntities(radius, radius, radius)) {
            if (!(entity instanceof LivingEntity target) || target.equals(owner) || target.isDead() || target.isInvulnerable()) continue;
            if (target.getLocation().distanceSquared(owner.getLocation()) > r2) continue;
            if (target instanceof Player p) {
                if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) continue;
                if (!p.getWorld().getPVP()) continue;
            } else if (!affectMobs) continue;
            result.add(target);
        }
        return result;
    }

    private void renderWindup(Player owner, Location center, List<LivingEntity> targets) {
        int windup = i("storm.ability.windup-ticks", 16, 4, 60);
        int interval = i("storm.visual.refresh-ticks", 2, 1, 10);
        double radius = d("storm.ability.radius", 7.0, 2.0, 24.0);
        double density = d("storm.visual.particle-density", 1.0, 0.1, 2.0);

        new BukkitRunnable() {
            int tick = 0;
            @Override public void run() {
                if (tick >= windup || !owner.isOnline()) {
                    cancel();
                    return;
                }
                double spin = tick * 0.32;
                int points = Math.max(8, host.scaleCosmeticCount((int)Math.round(28 * density)));
                for (int n = 0; n < points; n++) {
                    double angle = spin + Math.PI * 2.0 * n / points;
                    double ring = 1.0 + (radius * 0.65) * (n / (double)Math.max(1, points - 1));
                    Location at = center.clone().add(Math.cos(angle) * ring, 0.15 + (n % 5) * 0.22, Math.sin(angle) * ring);
                    center.getWorld().spawnParticle(Particle.CLOUD, at, 1, 0.05, 0.03, 0.05, 0.015);
                    if (n % 4 == 0) center.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, at, 1, 0.03, 0.08, 0.03, 0.02);
                }
                for (LivingEntity target : targets) {
                    if (!target.isValid()) continue;
                    target.getWorld().spawnParticle(Particle.WHITE_ASH, target.getLocation().add(0, 1, 0),
                            host.scaleCosmeticCount(Math.max(2, (int)Math.round(4 * density))),
                            0.55, 0.8, 0.55, 0.02);
                }
                tick += interval;
            }
        }.runTaskTimer(host, 0L, interval);
    }

    private void launchTargets(Player owner, List<LivingEntity> originalTargets) {
        List<LivingEntity> targets = new ArrayList<>();
        double launch = d("storm.ability.launch-power", 1.35, 0.2, 3.5);
        for (LivingEntity target : originalTargets) {
            if (!target.isValid() || target.isDead() || target.getWorld() != owner.getWorld()) continue;
            target.setFallDistance(0f);
            Vector old = target.getVelocity();
            target.setVelocity(new Vector(old.getX() * 0.18, launch, old.getZ() * 0.18));
            target.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, target.getLocation().add(0, 0.7, 0),
                    host.scaleCosmeticCount(10), 0.55, 0.7, 0.55, 0.08);
            targets.add(target);
        }

        playSound(owner.getWorld(), owner.getLocation(), "storm.sounds.launch", Sound.ENTITY_WIND_CHARGE_WIND_BURST);

        int hold = i("storm.ability.hold-ticks", 8, 0, 40);
        int launchRise = i("storm.ability.launch-rise-ticks", 10, 2, 30);
        Bukkit.getScheduler().runTaskLater(host, () -> {
            for (LivingEntity target : targets) {
                if (!target.isValid() || target.isDead()) continue;
                target.setFallDistance(0f);
                target.setVelocity(new Vector(0, 0.04, 0));
            }
            Bukkit.getScheduler().runTaskLater(host, () -> slamTargets(owner, targets), hold);
        }, launchRise);
    }

    private void slamTargets(Player owner, List<LivingEntity> targets) {
        double slam = d("storm.ability.slam-power", 2.8, 0.5, 5.0);
        for (LivingEntity target : targets) {
            if (!target.isValid() || target.isDead()) continue;
            target.setFallDistance(0f);
            Vector v = target.getVelocity();
            target.setVelocity(new Vector(v.getX() * 0.10, -slam, v.getZ() * 0.10));
        }
        playSound(owner.getWorld(), owner.getLocation(), "storm.sounds.slam", Sound.ENTITY_GENERIC_EXPLODE);
        monitorImpacts(owner, targets);
    }

    private void monitorImpacts(Player owner, List<LivingEntity> targets) {
        Set<UUID> done = new HashSet<>();
        int maxTicks = i("storm.ability.impact-timeout-ticks", 50, 10, 100);
        new BukkitRunnable() {
            int elapsed = 0;
            @Override public void run() {
                if (elapsed >= maxTicks || done.size() >= targets.size()) {
                    activeAbilities = Math.max(0, activeAbilities - 1);
                    cancel();
                    return;
                }
                for (LivingEntity target : targets) {
                    if (done.contains(target.getUniqueId())) continue;
                    if (!target.isValid() || target.isDead()) {
                        done.add(target.getUniqueId());
                        continue;
                    }
                    target.setFallDistance(0f);
                    boolean impact = elapsed >= 4 && (target.isOnGround() || Math.abs(target.getVelocity().getY()) < 0.08);
                    if (impact) {
                        done.add(target.getUniqueId());
                        impact(owner, target);
                    }
                }
                elapsed++;
            }
        }.runTaskTimer(host, 1L, 1L);
    }

    private void impact(Player owner, LivingEntity target) {
        World world = target.getWorld();
        Location at = target.getLocation();
        double density = d("storm.visual.particle-density", 1.0, 0.1, 2.0);
        world.spawnParticle(Particle.CLOUD, at, host.scaleCosmeticCount((int)Math.round(30 * density)), 1.1, 0.2, 1.1, 0.08);
        world.spawnParticle(Particle.ELECTRIC_SPARK, at.clone().add(0, 0.4, 0),
                host.scaleCosmeticCount((int)Math.round(24 * density)), 1.0, 0.55, 1.0, 0.10);
        world.spawnParticle(Particle.FLASH, at.clone().add(0, 0.8, 0), 1);
        if (getConfig().getBoolean("storm.visual.lightning-effect", true)) world.strikeLightningEffect(at);

        if (getConfig().getBoolean("storm.ability.damage-enabled", true)) {
            double damage = d("storm.ability.damage-hearts", 3.0, 0.0, 20.0) * 2.0;
            if (damage > 0.0) target.damage(damage, owner);
        }
        playSound(world, at, "storm.sounds.impact", Sound.ENTITY_LIGHTNING_BOLT_IMPACT);
    }

    private void playSound(World world, Location at, String path, Sound fallback) {
        if (world == null) return;
        String raw = getConfig().getString(path + ".sound", fallback.name());
        Sound sound = fallback;
        if (raw != null) {
            try { sound = Sound.valueOf(raw.trim().toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException ignored) { getLogger().warning("Unknown STORM sound: " + raw); }
        }
        world.playSound(at, sound, (float)d(path + ".volume", 1, 0, 5), (float)d(path + ".pitch", 1, 0.01, 2));
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
        inv.setItem(46, named(Material.COMPARATOR, "&b🌪 Настройки способности", List.of("&7Радиус, подброс, slam, урон, cooldown.")));
        inv.setItem(48, named(Material.PLAYER_HEAD, "&f👁 Preview STORM", generatedBonusLore()));
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
        boolean damage = getConfig().getBoolean("storm.ability.damage-enabled", true);
        inv.setItem(31, named(damage ? Material.LIME_CONCRETE : Material.RED_CONCRETE,
                damage ? "&aSlam Damage: ON" : "&cSlam Damage: OFF",
                List.of("&7Нажмите, чтобы включить/выключить", "&7дополнительный урон способности.")));
        inv.setItem(44, named(Material.ARROW, "&e← Назад", List.of()));
        player.openInventory(inv);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEditorClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        String title = event.getView().getTitle();
        if (!title.equals(MAIN_TITLE) && !title.equals(ADD_TITLE) && !title.equals(ABILITY_TITLE)) return;
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
            if (event.getRawSlot() == 31) {
                getConfig().set("storm.ability.damage-enabled", !getConfig().getBoolean("storm.ability.damage-enabled", true));
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
        if (title.equals(MAIN_TITLE) || title.equals(ADD_TITLE) || title.equals(ABILITY_TITLE)) event.setCancelled(true);
    }

    private void editProperty(Player player, String id, ClickType click) {
        PropertySpec spec = PROPERTY_SPECS.get(id);
        if (spec == null) return;
        String base = "storm.item.properties." + id;
        if (click == ClickType.DROP || click == ClickType.CONTROL_DROP) {
            getConfig().set(base, null);
            saveRefresh();
            player.sendMessage(color("&cУдалено свойство: &f" + spec.name()));
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
        getConfig().set(base + ".enabled", true);
        getConfig().set(base + ".value", spec.defaultValue());
        getConfig().set(base + ".display", spec.defaultDisplay());
        int maxOrder = activeProperties().stream().mapToInt(PropertyEntry::order).max().orElse(0);
        getConfig().set(base + ".order", maxOrder + 10);
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
            entries.add(new PropertyEntry(
                    id,
                    getConfig().getDouble(path + ".value", spec.defaultValue()),
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

    private String formatCommon(String raw) {
        if (raw == null) return "";
        return raw
                .replace("%ability%", getConfig().getString("storm.ability.name", "Око Бури"))
                .replace("%radius%", number(d("storm.ability.radius", 7.0, 2.0, 24.0)))
                .replace("%cooldown%", String.valueOf(i("storm.ability.cooldown-seconds", 60, 1, 86400)))
                .replace("%storm_damage%", number(d("storm.ability.damage-hearts", 3.0, 0.0, 20.0)));
    }

    private void applyTexture(SkullMeta meta, String value) {
        try {
            PlayerProfile profile = Bukkit.createProfile(UUID.randomUUID(), "StormBall");
            profile.setProperty(new ProfileProperty("textures", value));
            meta.setPlayerProfile(profile);
        } catch (Exception e) {
            getLogger().warning("Could not apply STORM texture: " + e.getMessage());
        }
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
