package me.saminasian.spheres;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.EulerAngle;
import org.bukkit.util.Vector;

public final class SantaSphere
extends SphereModule
implements Listener {
    public SantaSphere(SpheresPlugin host) { super(host); }
    private static final double HARD_MAX_TARGET_RANGE = 32.0;
    private static final int HARD_MAX_SANTAS = 12;
    private static final int HARD_MAX_GIFTS = 100;
    private NamespacedKey santaBallKey;
    private final Map<UUID, Long> cooldowns = new HashMap<UUID, Long>();
    private final Map<UUID, Integer> giftRewards = new HashMap<UUID, Integer>();
    private final Set<Entity> temporaryEntities = new HashSet<Entity>();
    private final Random random = new Random();
    private int activeAbilities = 0;

    public void start() {
        this.santaBallKey = new NamespacedKey(host, "santa_ball");
        if (!this.getDataFolder().exists() && !this.getDataFolder().mkdirs()) {
            this.getLogger().warning("Could not create plugin data folder.");
        }
        this.loadCooldowns();
        Bukkit.getPluginManager().registerEvents((Listener)this, host);
        this.getLogger().info("Santaball 1.4 enabled for Leaf / Paper 1.21.11");
    }

    public void stop() {
        Bukkit.getScheduler().cancelTasks(host);
        this.saveCooldownSnapshot(new HashMap<UUID, Long>(this.cooldowns));
        for (Entity entity : new ArrayList<Entity>(this.temporaryEntities)) {
            if (entity == null) continue;
            entity.remove();
        }
        this.temporaryEntities.clear();
        this.giftRewards.clear();
        this.activeAbilities = 0;
        this.cooldowns.clear();
        SantaNpcOverlay.shutdown();
    }

    @EventHandler(ignoreCancelled=true)
    public void onSneak(PlayerToggleSneakEvent playerToggleSneakEvent) {
        if (!playerToggleSneakEvent.isSneaking()) {
            return;
        }
        Player player = playerToggleSneakEvent.getPlayer();
        if (!this.isSantaBall(player.getInventory().getItemInOffHand())) {
            return;
        }
        long l = this.remainingCooldownSeconds(player);
        if (l > 0L) {
            this.sendCooldownMessage(player, l);
            return;
        }
        Player player2 = this.findNearestTarget(player);
        if (player2 == null) {
            player.sendMessage(this.color(this.getConfig().getString("santa.ability.no-target-message", "&cРядом нет игрока для способности.")));
            return;
        }
        int n = this.i("santa.performance.max-concurrent-abilities", 2, 1, 2);
        if (this.activeAbilities >= n) {
            player.sendMessage(this.color(this.getConfig().getString("santa.messages.busy", "&eСлишком много новогодних эффектов одновременно. Попробуйте через пару секунд.")));
            return;
        }
        Set<Entity> existingEntities = new HashSet<>(this.temporaryEntities);
        ++this.activeAbilities;
        this.startCooldown(player);
        try {
            this.activate(player, player2);
            int n2 = this.calculateAbilityLifetimeTicks();
            Bukkit.getScheduler().runTaskLater(host, () -> {
                this.activeAbilities = Math.max(0, this.activeAbilities - 1);
            }, (long)n2);
        }
        catch (Throwable throwable) {
            for (Entity spawned : new ArrayList<>(temporaryEntities)) {
                if (!existingEntities.contains(spawned)) removeTemporary(spawned);
            }
            this.activeAbilities = Math.max(0, this.activeAbilities - 1);
            this.cooldowns.remove(player.getUniqueId());
            this.saveCooldownsNow();
            this.getLogger().warning("Ability failed safely: " + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
            player.sendMessage(this.color(this.getConfig().getString("santa.messages.failed", "&cСпособность не смогла запуститься. Кулдаун не потрачен.")));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onHopper(org.bukkit.event.inventory.InventoryPickupItemEvent event) {
        if (temporaryEntities.contains(event.getItem())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onGiftPickup(org.bukkit.event.entity.EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }

        Item item = event.getItem();
        Integer amount = this.giftRewards.remove(item.getUniqueId());
        if (amount == null) {
            return;
        }

        // A gift is a reward trigger, not an item that should remain in inventory.
        event.setCancelled(true);
        Location pickup = item.getLocation().clone();
        this.removeTemporary(item);

        String command = this.getConfig().getString(
                "santa.ability.santas.gifts.reward.command",
                "eco give %player% %amount%"
        );
        if (command != null && !command.isBlank() && amount > 0) {
            Bukkit.dispatchCommand(
                    Bukkit.getConsoleSender(),
                    command.replace("%player%", player.getName())
                           .replace("%amount%", String.valueOf(amount))
            );
        }

        String message = this.getConfig().getString(
                "santa.ability.santas.gifts.reward.message",
                "&a+%amount% монет за подарок!"
        );
        if (message != null && !message.isBlank()) {
            player.sendMessage(this.color(
                    message.replace("%player%", player.getName())
                           .replace("%amount%", String.valueOf(amount))
            ));
        }

        World world = pickup.getWorld();
        if (world != null) {
            world.spawnParticle(Particle.END_ROD, pickup.clone().add(0.0, 0.4, 0.0),
                    10, 0.25, 0.35, 0.25, 0.03);
            world.spawnParticle(Particle.SNOWFLAKE, pickup.clone().add(0.0, 0.4, 0.0),
                    14, 0.35, 0.35, 0.35, 0.04);
            world.playSound(pickup, Sound.ENTITY_PLAYER_LEVELUP, 0.55f, 1.7f);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onManipulate(org.bukkit.event.player.PlayerArmorStandManipulateEvent event) {
        if (temporaryEntities.contains(event.getRightClicked())) event.setCancelled(true);
    }
    private void activate(Player player, Player player2) {
        World world = player.getWorld();
        if (player2.getWorld() != world) {
            throw new IllegalStateException("Target changed world before activation");
        }
        this.playConfiguredSound(world, player.getLocation(), "santa.ability.sounds.start", Sound.ENTITY_PLAYER_LEVELUP);
        List<ArmorStand> list = this.spawnSantas(player, player2);
        this.startSantaParticleLoop(list);
        this.startGiftThrowing(list);
        this.startAvalanche(player, player2);
    }

    private Player findNearestTarget(Player player) {
        if (!player.getWorld().getPVP() || player.isDead() || player.getGameMode() == GameMode.SPECTATOR) return null;
        double d = this.configuredTargetRange();
        double d2 = d * d;
        Location location = player.getLocation();
        Player player2 = null;
        double d3 = d2;
        for (Player player3 : player.getWorld().getPlayers()) {
            double d4;
            if (player3.getUniqueId().equals(player.getUniqueId()) || !player3.isOnline() || player3.isDead() || player3.isInvulnerable() || player3.getGameMode() == GameMode.SPECTATOR || player3.getGameMode() == GameMode.CREATIVE || !player.canSee(player3) || !((d4 = player3.getLocation().distanceSquared(location)) <= d3)) continue;
            d3 = d4;
            player2 = player3;
        }
        return player2;
    }

    private long remainingCooldownSeconds(Player player) {
        long l = System.currentTimeMillis();
        long l2 = (long)this.i("santa.ability.cooldown-seconds", 300, 1, 86400) * 1000L;
        Long l3 = this.cooldowns.get(player.getUniqueId());
        if (l3 == null) {
            return 0L;
        }
        long l4 = l2 - (l - l3);
        if (l4 <= 0L) {
            this.cooldowns.remove(player.getUniqueId());
            return 0L;
        }
        return (l4 + 999L) / 1000L;
    }

    private void sendCooldownMessage(Player player, long l) {
        String string = this.getConfig().getString("santa.ability.name", "Рождественская Лавина");
        String string2 = this.getConfig().getString("santa.ability.cooldown-message", "&6Задержка: &f%ability% будет доступна через &c%time% сек.");
        player.sendMessage(this.color(string2.replace("%ability%", string).replace("%time%", String.valueOf(l))));
    }

    private void startCooldown(Player player) {
        this.cooldowns.put(player.getUniqueId(), System.currentTimeMillis());
        this.saveCooldownsNow();
    }

    private List<ArmorStand> spawnSantas(Player player, Player player2) {
        final ArrayList<ArmorStand> arrayList = new ArrayList<ArmorStand>();
        if (!this.getConfig().getBoolean("santa.ability.santas.enabled", true)) {
            return arrayList;
        }
        int n = this.i("santa.ability.santas.count", 10, 1, HARD_MAX_SANTAS);
        double minRadius = this.d("santa.ability.santas.random-min-radius", 2.0, 0.5, 12.0);
        double maxRadius = this.d("santa.ability.santas.random-max-radius", 7.0, minRadius, 16.0);
        int n2 = this.i("santa.ability.santas.lifetime-ticks", 180, 40, 400);
        Location location = player.getLocation().clone();
        World world = location.getWorld();
        if (world == null) {
            return arrayList;
        }
        ItemStack itemStack = this.createTexturedHead(this.getConfig().getString("santa.item.santa-head-texture-url", "http://textures.minecraft.net/texture/8a159236d7512bdb4326a24e14502167b76bcd85c041931c2194201b17f5e7"), "&cSanta");
        for (int i = 0; i < n; ++i) {
            Location location2 = this.findRandomSantaLocation(location, minRadius, maxRadius, arrayList);
            ArmorStand armorStand2 = (ArmorStand)world.spawn(location2, ArmorStand.class, armorStand -> {
                armorStand.setArms(true);
                armorStand.setBasePlate(false);
                armorStand.setGravity(false);
                armorStand.setInvulnerable(true);
                for (org.bukkit.inventory.EquipmentSlot slot : org.bukkit.inventory.EquipmentSlot.values()) {
                    if (slot == org.bukkit.inventory.EquipmentSlot.BODY || slot == org.bukkit.inventory.EquipmentSlot.SADDLE) continue;
                    for (ArmorStand.LockType lock : ArmorStand.LockType.values()) armorStand.addEquipmentLock(slot, lock);
                }
                armorStand.setSilent(true);
                armorStand.setCollidable(false);
                armorStand.setPersistent(false);
                armorStand.setCustomNameVisible(false);
                armorStand.setHeadPose(new EulerAngle(0.0, 0.0, 0.0));
            });
            this.equipSanta(armorStand2, itemStack);
            if (this.getConfig().getBoolean("santa.ability.santas.face-target", true)) {
                this.faceLocation(armorStand2, player2.getEyeLocation());
            }
            this.temporaryEntities.add((Entity)armorStand2);
            arrayList.add(armorStand2);
        }
        new BukkitRunnable(){

            public void run() {
                for (ArmorStand armorStand : arrayList) {
                    SantaSphere.this.removeTemporary((Entity)armorStand);
                }
            }
        }.runTaskLater(host, (long)n2);
        SantaNpcOverlay.apply(this, arrayList, player, player2);
        return arrayList;
    }

    private Location findRandomSantaLocation(
            Location center,
            double minRadius,
            double maxRadius,
            List<ArmorStand> existing
    ) {
        World world = center.getWorld();
        if (world == null) {
            return center.clone();
        }

        for (int attempt = 0; attempt < 32; attempt++) {
            double angle = this.random.nextDouble() * Math.PI * 2.0;
            double distance = minRadius + this.random.nextDouble() * Math.max(0.01, maxRadius - minRadius);
            double x = center.getX() + Math.cos(angle) * distance;
            double z = center.getZ() + Math.sin(angle) * distance;
            int blockX = (int)Math.floor(x);
            int blockZ = (int)Math.floor(z);

            // Stay close to the owner's floor level, but allow small stairs/slopes.
            for (int y = center.getBlockY() + 3; y >= center.getBlockY() - 4; y--) {
                Material floor = world.getBlockAt(blockX, y - 1, blockZ).getType();
                Material feet = world.getBlockAt(blockX, y, blockZ).getType();
                Material head = world.getBlockAt(blockX, y + 1, blockZ).getType();
                if (!floor.isSolid() || feet.isSolid() || head.isSolid()) {
                    continue;
                }

                Location candidate = new Location(world, blockX + 0.5, y, blockZ + 0.5);
                boolean tooClose = false;
                for (ArmorStand other : existing) {
                    if (other.isValid() && other.getLocation().distanceSquared(candidate) < 2.25) {
                        tooClose = true;
                        break;
                    }
                }
                if (!tooClose) {
                    return candidate;
                }
            }
        }

        // Extremely cramped location: still avoid a crash and keep the effect usable.
        return center.clone().add(0.0, 0.1, 0.0);
    }

    private void equipSanta(ArmorStand armorStand, ItemStack itemStack) {
        EntityEquipment entityEquipment = armorStand.getEquipment();
        if (entityEquipment == null) {
            return;
        }
        entityEquipment.setHelmet(itemStack.clone());
        entityEquipment.setChestplate(this.coloredLeather(Material.LEATHER_CHESTPLATE, Color.fromRGB((int)190, (int)20, (int)25)));
        entityEquipment.setLeggings(this.coloredLeather(Material.LEATHER_LEGGINGS, Color.fromRGB((int)190, (int)20, (int)25)));
        entityEquipment.setBoots(this.coloredLeather(Material.LEATHER_BOOTS, Color.fromRGB((int)28, (int)28, (int)28)));
        entityEquipment.setItemInMainHand(new ItemStack(Material.SNOWBALL));
    }

    private ItemStack coloredLeather(Material material, Color color) {
        ItemStack itemStack = new ItemStack(material);
        ItemMeta itemMeta = itemStack.getItemMeta();
        if (itemMeta instanceof LeatherArmorMeta) {
            LeatherArmorMeta leatherArmorMeta = (LeatherArmorMeta)itemMeta;
            leatherArmorMeta.setColor(color);
            leatherArmorMeta.addItemFlags(new ItemFlag[]{ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_DYE});
            itemStack.setItemMeta((ItemMeta)leatherArmorMeta);
        }
        return itemStack;
    }

    private void startSantaParticleLoop(final List<ArmorStand> list) {
        if (list.isEmpty() || !this.getConfig().getBoolean("santa.ability.santas.particles.enabled", true)) {
            return;
        }
        final int n = this.i("santa.ability.santas.lifetime-ticks", 180, 40, 400);
        final int n2 = this.i("santa.ability.santas.particles.refresh-ticks", 3, 1, 20);
        final int n3 = this.i("santa.ability.santas.particles.red-count-per-santa", 5, 0, 20);
        final int n4 = this.i("santa.ability.santas.particles.green-count-per-santa", 4, 0, 20);
        final int n5 = this.i("santa.ability.santas.particles.snow-count-per-santa", 4, 0, 16);
        final int sparkCount = this.i("santa.ability.santas.particles.spark-count-per-santa", 2, 0, 12);
        final double d = this.d("santa.ability.santas.particles.spread", 0.45, 0.0, 1.5);
        float f = (float)this.d("santa.ability.santas.particles.size", 1.30, 0.1, 3.0);
        final Particle.DustOptions dustOptions = new Particle.DustOptions(Color.fromRGB((int)255, (int)35, (int)35), f);
        final Particle.DustOptions dustOptions2 = new Particle.DustOptions(Color.fromRGB((int)45, (int)220, (int)70), f);
        new BukkitRunnable(){
            int lived = 0;

            public void run() {
                if (this.lived >= n || list.stream().noneMatch(Entity::isValid)) {
                    this.cancel();
                    return;
                }
                for (ArmorStand armorStand : list) {
                    Location location;
                    World world;
                    if (!armorStand.isValid() || (world = (location = armorStand.getLocation().add(0.0, 1.1, 0.0)).getWorld()) == null) continue;
                    if (n3 > 0) {
                        world.spawnParticle(Particle.DUST, location, n3, d, 0.45, d, 0.0, (Object)dustOptions);
                    }
                    if (n4 > 0) {
                        world.spawnParticle(Particle.DUST, location, n4, d, 0.45, d, 0.0, (Object)dustOptions2);
                    }
                    if (n5 > 0) {
                        world.spawnParticle(Particle.SNOWFLAKE, location, n5, d, 0.5, d, 0.03);
                    }
                    if (sparkCount > 0) {
                        world.spawnParticle(Particle.END_ROD, location, sparkCount, d * 0.8, 0.45, d * 0.8, 0.01);
                    }
                }
                this.lived += n2;
            }
        }.runTaskTimer(host, 0L, (long)n2);
    }

    private void startGiftThrowing(final List<ArmorStand> list) {
        if (list.isEmpty() || !this.getConfig().getBoolean("santa.ability.santas.gifts.enabled", true)) {
            return;
        }
        final int n = this.i("santa.ability.santas.gifts.total", 60, 1, HARD_MAX_GIFTS);
        int n2 = this.i("santa.ability.santas.gifts.interval-ticks", 2, 1, 20);
        final int n3 = this.i("santa.ability.santas.gifts.lifetime-ticks", 100, 20, 300);
        final int rewardAmount = this.i("santa.ability.santas.gifts.reward.amount", 100, 0, 100000000);
        final double d = this.d("santa.ability.santas.gifts.launch-horizontal", 0.38, 0.0, 2.0);
        final double d2 = this.d("santa.ability.santas.gifts.launch-up", 0.48, 0.0, 2.0);
        String string = this.getConfig().getString("santa.ability.santas.gifts.textures.red", "http://textures.minecraft.net/texture/b73a2114136b8ee4926caa51785414036a2b76e4f1668cb89d99716c421");
        String string2 = this.getConfig().getString("santa.ability.santas.gifts.textures.green", "http://textures.minecraft.net/texture/884b8a32bc6de2884ba31398d5b028d1a4fa77f59a415b7e6f62f2623f4f6");
        final ItemStack itemStack = this.createTexturedHead(string, "&cПодарок");
        final ItemStack itemStack2 = this.createTexturedHead(string2, "&aПодарок");
        new BukkitRunnable(){
            int spawned = 0;

            public void run() {
                if (this.spawned >= n || list.stream().noneMatch(Entity::isValid)) {
                    this.cancel();
                    return;
                }
                List<ArmorStand> list2 = list.stream().filter(Entity::isValid).toList();
                if (list2.isEmpty()) {
                    this.cancel();
                    return;
                }
                ArmorStand armorStand = list2.get(SantaSphere.this.random.nextInt(list2.size()));
                ItemStack itemStack3 = SantaSphere.this.random.nextBoolean() ? itemStack.clone() : itemStack2.clone();
                Location location = armorStand.getLocation().add(0.0, 1.1, 0.0);
                World world = location.getWorld();
                if (world == null) {
                    return;
                }
                final Item item = world.dropItem(location, itemStack3);
                item.setPickupDelay(10);
                item.setPersistent(false);
                item.setCanMobPickup(false);
                item.setCanPlayerPickup(true);
                item.setWillAge(false);
                item.setVelocity(new Vector((SantaSphere.this.random.nextDouble() - 0.5) * d * 2.0, d2 + SantaSphere.this.random.nextDouble() * 0.15, (SantaSphere.this.random.nextDouble() - 0.5) * d * 2.0));
                SantaSphere.this.temporaryEntities.add((Entity)item);
                SantaSphere.this.giftRewards.put(item.getUniqueId(), rewardAmount);
                world.spawnParticle(Particle.END_ROD, location, 5, 0.20, 0.25, 0.20, 0.01);
                SantaSphere.this.playConfiguredSound(world, location, "santa.ability.sounds.gift", Sound.ENTITY_ITEM_PICKUP);
                new BukkitRunnable(){

                    public void run() {
                        SantaSphere.this.removeTemporary((Entity)item);
                    }
                }.runTaskLater(host, (long)n3);
                ++this.spawned;
            }
        }.runTaskTimer(host, 0L, (long)n2);
    }

    private void startAvalanche(final Player owner, final Player primaryTarget) {
        if (!this.getConfig().getBoolean("santa.ability.avalanche.enabled", true)) {
            return;
        }

        final double areaRadius = this.d("santa.ability.avalanche.area-radius", 12.0, 2.0, 24.0);
        final int maxTargets = this.i("santa.ability.avalanche.max-targets", 5, 1, 8);
        final int staggerMax = this.i("santa.ability.avalanche.stagger-ticks-max", 6, 0, 20);

        List<Player> targets = this.findAvalancheTargets(
                owner,
                primaryTarget.getLocation(),
                areaRadius,
                maxTargets
        );
        if (targets.isEmpty()) {
            return;
        }

        this.startSnowstorm(owner, targets);

        // Shared set: overlapping snowball impacts from the same avalanche may
        // visually overlap, but one player can only take the combat effects once.
        final Set<UUID> alreadyHit = new HashSet<UUID>();

        for (Player target : targets) {
            long delay = staggerMax <= 0 ? 0L : this.random.nextInt(staggerMax + 1);
            new BukkitRunnable() {
                @Override
                public void run() {
                    if (!owner.isOnline() || owner.isDead()
                            || !target.isOnline() || target.isDead()
                            || owner.getWorld() != target.getWorld()) {
                        return;
                    }
                    SantaSphere.this.startSnowballStrike(owner, target, alreadyHit);
                }
            }.runTaskLater(host, delay);
        }
    }

    private List<Player> findAvalancheTargets(Player owner, Location center, double radius, int maxTargets) {
        if (!owner.getWorld().getPVP() || center.getWorld() != owner.getWorld()) {
            return List.of();
        }

        double radiusSquared = radius * radius;
        ArrayList<Player> targets = new ArrayList<Player>();
        for (Player candidate : owner.getWorld().getPlayers()) {
            if (candidate.getUniqueId().equals(owner.getUniqueId())
                    || !candidate.isOnline()
                    || candidate.isDead()
                    || candidate.isInvulnerable()
                    || candidate.getGameMode() == GameMode.SPECTATOR
                    || candidate.getGameMode() == GameMode.CREATIVE
                    || !owner.canSee(candidate)
                    || candidate.getLocation().distanceSquared(center) > radiusSquared) {
                continue;
            }
            targets.add(candidate);
        }

        targets.sort(Comparator.comparingDouble(p -> p.getLocation().distanceSquared(center)));
        if (targets.size() > maxTargets) {
            return new ArrayList<Player>(targets.subList(0, maxTargets));
        }
        return targets;
    }

    private void startSnowstorm(final Player owner, final List<Player> targets) {
        if (!this.getConfig().getBoolean("santa.ability.avalanche.storm.enabled", true)) {
            return;
        }

        final int duration = this.i("santa.ability.avalanche.storm.duration-ticks", 60, 10, 200);
        final int refresh = this.i("santa.ability.avalanche.storm.refresh-ticks", 4, 2, 20);
        final int snow = this.i("santa.ability.avalanche.storm.snow-count-per-target", 14, 0, 60);
        final int ash = this.i("santa.ability.avalanche.storm.white-ash-count-per-target", 10, 0, 60);
        final int cloud = this.i("santa.ability.avalanche.storm.cloud-count-per-target", 5, 0, 30);
        final double horizontal = this.d("santa.ability.avalanche.storm.horizontal-spread", 2.6, 0.5, 6.0);
        final double vertical = this.d("santa.ability.avalanche.storm.vertical-spread", 1.8, 0.5, 4.0);

        new BukkitRunnable() {
            private int lived = 0;

            @Override
            public void run() {
                if (!owner.isOnline() || owner.isDead() || lived >= duration) {
                    cancel();
                    return;
                }

                boolean any = false;
                for (Player target : targets) {
                    if (!target.isOnline() || target.isDead() || target.getWorld() != owner.getWorld()) {
                        continue;
                    }
                    any = true;
                    World world = target.getWorld();
                    Location fog = target.getEyeLocation().clone().add(0.0, -0.35, 0.0);

                    if (snow > 0) {
                        world.spawnParticle(
                                Particle.SNOWFLAKE,
                                fog,
                                snow,
                                horizontal,
                                vertical,
                                horizontal,
                                0.055
                        );
                    }
                    if (ash > 0) {
                        world.spawnParticle(
                                Particle.WHITE_ASH,
                                fog,
                                ash,
                                horizontal * 0.85,
                                vertical,
                                horizontal * 0.85,
                                0.015
                        );
                    }
                    if (cloud > 0) {
                        world.spawnParticle(
                                Particle.CLOUD,
                                fog,
                                cloud,
                                horizontal * 0.55,
                                vertical * 0.65,
                                horizontal * 0.55,
                                0.018
                        );
                    }
                }

                if (!any) {
                    cancel();
                    return;
                }
                lived += refresh;
            }
        }.runTaskTimer(host, 0L, refresh);
    }

    private void startSnowballStrike(final Player owner, final Player target, final Set<UUID> alreadyHit) {
        final int fallDuration = this.i("santa.ability.avalanche.fall-duration-ticks", 24, 10, 60);
        final int updateInterval = this.i("santa.ability.avalanche.update-interval-ticks", 2, 1, 4);
        final int trackTicks = this.i("santa.ability.avalanche.track-target-ticks", 10, 0, fallDuration);
        final double spawnHeight = this.d("santa.ability.avalanche.spawn-height", 6.0, 5.0, 7.0);
        final int visualRadius = this.i("santa.ability.avalanche.sphere-radius-blocks", 3, 1, 3);
        final int fallingSnow = this.i("santa.ability.avalanche.particles.falling-snow-count", 12, 0, 80);
        final int fallingCloud = this.i("santa.ability.avalanche.particles.falling-cloud-count", 3, 0, 30);

        final Location impactBase = target.getLocation().clone();
        final World world = impactBase.getWorld();
        if (world == null) {
            return;
        }

        // Seven displays per snowball: one center + six axis blocks. The spread
        // makes it look large without the old 27-display cost per target.
        final List<Vector> offsets = this.makeSnowSphereOffsets(visualRadius);
        final ArrayList<BlockDisplay> displays = new ArrayList<BlockDisplay>();
        final BlockData blockData = Material.SNOW_BLOCK.createBlockData();
        final Location spawnCenter = target.getEyeLocation().clone().add(0.0, spawnHeight, 0.0);
        final double verticalTravel = spawnCenter.getY() - impactBase.getY();

        for (Vector offset : offsets) {
            Location spawn = spawnCenter.clone().add(offset);
            BlockDisplay display = (BlockDisplay)world.spawn(spawn, BlockDisplay.class, blockDisplay -> {
                blockDisplay.setBlock(blockData);
                blockDisplay.setTeleportDuration(updateInterval);
                blockDisplay.setInvulnerable(true);
                blockDisplay.setPersistent(false);
                blockDisplay.setGlowing(true);
                blockDisplay.setGlowColorOverride(Color.WHITE);
            });
            displays.add(display);
            this.temporaryEntities.add((Entity)display);
        }

        this.playConfiguredSound(world, spawnCenter, "santa.ability.sounds.falling", Sound.ENTITY_SNOWBALL_THROW);

        new BukkitRunnable() {
            private int elapsed = 0;
            private double centerX = spawnCenter.getX();
            private double centerZ = spawnCenter.getZ();

            @Override
            public void run() {
                if (!owner.isOnline() || owner.isDead() || owner.getWorld() != world
                        || !target.isOnline() || target.isDead() || target.getWorld() != world) {
                    displays.forEach(SantaSphere.this::removeTemporary);
                    cancel();
                    return;
                }

                if (elapsed >= fallDuration) {
                    SantaSphere.this.impact(
                            owner,
                            new Location(world, centerX, target.getLocation().getY(), centerZ),
                            displays,
                            alreadyHit
                    );
                    cancel();
                    return;
                }

                Location targetLocation = target.getLocation();
                if (elapsed < trackTicks) {
                    centerX += (targetLocation.getX() - centerX) * 0.42;
                    centerZ += (targetLocation.getZ() - centerZ) * 0.42;
                }

                double progress = Math.min(1.0, (double)(elapsed + updateInterval) / (double)fallDuration);
                double eased = progress * progress;
                double y = impactBase.getY() + verticalTravel * (1.0 - eased);
                Location center = new Location(world, centerX, y, centerZ);

                for (int i = 0; i < displays.size(); ++i) {
                    BlockDisplay display = displays.get(i);
                    if (!display.isValid()) {
                        continue;
                    }
                    display.teleport(center.clone().add(offsets.get(i)));
                }

                if (fallingSnow > 0) {
                    world.spawnParticle(
                            Particle.SNOWFLAKE,
                            center,
                            fallingSnow,
                            visualRadius * 0.62,
                            visualRadius * 0.62,
                            visualRadius * 0.62,
                            0.06
                    );
                }
                if (fallingCloud > 0 && elapsed % 4 == 0) {
                    world.spawnParticle(
                            Particle.CLOUD,
                            center,
                            fallingCloud,
                            visualRadius * 0.32,
                            visualRadius * 0.32,
                            visualRadius * 0.32,
                            0.015
                    );
                }

                elapsed += updateInterval;
            }
        }.runTaskTimer(host, 0L, updateInterval);
    }

    private List<Vector> makeSnowSphereOffsets(int n) {
        ArrayList<Vector> offsets = new ArrayList<Vector>();
        offsets.add(new Vector(0.0, 0.0, 0.0));

        double axis = n == 1 ? 0.85 : (n == 2 ? 1.30 : 1.75);
        offsets.add(new Vector(axis, 0.0, 0.0));
        offsets.add(new Vector(-axis, 0.0, 0.0));
        offsets.add(new Vector(0.0, axis, 0.0));
        offsets.add(new Vector(0.0, -axis, 0.0));
        offsets.add(new Vector(0.0, 0.0, axis));
        offsets.add(new Vector(0.0, 0.0, -axis));
        return offsets;
    }

    private void impact(Player player, Location location, List<BlockDisplay> list) {
        this.impact(player, location, list, new HashSet<UUID>());
    }

    private void impact(Player player, Location location, List<BlockDisplay> list, Set<UUID> alreadyHit) {
        for (BlockDisplay blockDisplay : list) {
            this.removeTemporary((Entity)blockDisplay);
        }
        World world = location.getWorld();
        if (world == null) {
            return;
        }
        this.playConfiguredSound(world, location, "santa.ability.sounds.impact", Sound.ENTITY_GENERIC_EXPLODE);
        int impactSnow = this.i("santa.ability.avalanche.particles.impact-snow-count", 100, 0, 300);
        int impactCloud = this.i("santa.ability.avalanche.particles.impact-cloud-count", 30, 0, 150);
        int impactBlock = this.i("santa.ability.avalanche.particles.impact-block-count", 40, 0, 150);
        if (impactSnow > 0) {
            world.spawnParticle(Particle.SNOWFLAKE, location.clone().add(0.0, 1.0, 0.0),
                    impactSnow, 2.3, 1.35, 2.3, 0.14);
        }
        if (impactCloud > 0) {
            world.spawnParticle(Particle.CLOUD, location.clone().add(0.0, 0.7, 0.0),
                    impactCloud, 1.9, 0.9, 1.9, 0.09);
        }
        if (impactBlock > 0) {
            world.spawnParticle(Particle.BLOCK, location.clone().add(0.0, 0.6, 0.0),
                    impactBlock, 1.8, 0.9, 1.8, 0.10, (Object)Material.SNOW_BLOCK.createBlockData());
        }
        world.spawnParticle(Particle.END_ROD, location.clone().add(0.0, 1.0, 0.0),
                18, 1.0, 0.8, 1.0, 0.04);
        double d = this.d("santa.ability.avalanche.impact-radius", 3.5, 0.5, 6.0);
        double d2 = this.d("santa.ability.avalanche.damage-hearts", 4.0, 0.0, 20.0) * 2.0;
        double d3 = this.d("santa.ability.avalanche.knockback-horizontal", 0.75, 0.0, 3.0);
        double d4 = this.d("santa.ability.avalanche.knockback-up", 0.45, 0.0, 2.0);
        boolean bl = this.getConfig().getBoolean("santa.ability.avalanche.slowness.enabled", true);
        int n = this.secondsToTicks(this.d("santa.ability.avalanche.slowness.seconds", 2.5, 0.0, 20.0));
        int n2 = this.i("santa.ability.avalanche.slowness.level", 2, 1, 10);
        for (Entity entity : world.getNearbyEntities(location, d, d, d)) {
            Player player2;
            if (!world.getPVP() || !(entity instanceof Player) || (player2 = (Player)entity).isDead() || player2.isInvulnerable() || player2.getGameMode() == GameMode.SPECTATOR || player2.getGameMode() == GameMode.CREATIVE || !player.canSee(player2) || entity.getUniqueId().equals(player.getUniqueId()) || entity.getLocation().distanceSquared(location) > d * d) continue;
            if (alreadyHit.contains(player2.getUniqueId())) {
                continue;
            }
            if (d2 <= 0.0) {
                var permission = new org.bukkit.event.entity.EntityDamageByEntityEvent(player, player2,
                        org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_ATTACK, 0.0);
                Bukkit.getPluginManager().callEvent(permission);
                if (permission.isCancelled()) continue;
            }
            if (d2 > 0.0) {
                double d5 = player2.getHealth() + player2.getAbsorptionAmount();
                player2.damage(d2, (Entity)player);
                double d6 = player2.getHealth() + player2.getAbsorptionAmount();
                if (d6 >= d5 - 1.0E-6 || player2.isDead()) continue;
            }
            alreadyHit.add(player2.getUniqueId());
            if (d3 > 0.0 || d4 > 0.0) {
                Vector vector = player2.getLocation().toVector().subtract(location.toVector());
                vector.setY(0.0);
                if (vector.lengthSquared() < 1.0E-4) {
                    vector = new Vector(this.random.nextDouble() - 0.5, 0.0, this.random.nextDouble() - 0.5);
                }
                vector.normalize().multiply(d3).setY(d4);
                player2.setVelocity(player2.getVelocity().add(vector));
            }
            if (!bl || n <= 0) continue;
            player2.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, n, n2 - 1, false, true));
        }
    }

    public ItemStack createSantaBall() {
        ItemStack itemStack = this.createTexturedHead(
                this.getConfig().getString(
                        "santa.item.ball-texture-url",
                        "http://textures.minecraft.net/texture/884e92487c6749995b79737b8a9eb4c43954797a6dd6cd9b4efce17cf475846"
                ),
                this.getConfig().getString("santa.item.name", "&c&lШАР &f❄ &cSANTA")
        );
        ItemMeta itemMeta = itemStack.getItemMeta();
        if (itemMeta == null) {
            return itemStack;
        }
        double d = this.configuredTargetRange();
        int n = this.i("santa.ability.cooldown-seconds", 300, 1, 86400);
        ArrayList<String> arrayList = new ArrayList<String>();
        List<String> description = this.getConfig().getStringList("santa.item.description");
        if (description.isEmpty()) {
            description = this.getConfig().getStringList("santa.item.lore");
        }
        for (String string : description) {
            arrayList.add(this.color(string.replace("%range%", this.cleanNumber(d)).replace("%cooldown%", String.valueOf(n))));
        }
        itemMeta.setLore(arrayList);
        itemMeta.getPersistentDataContainer().set(this.santaBallKey, PersistentDataType.BYTE, (byte)1);
        itemStack.setItemMeta(itemMeta);
        return itemStack;
    }

    private boolean isSantaBall(ItemStack itemStack) {
        if (itemStack == null || itemStack.getType() != Material.PLAYER_HEAD || !itemStack.hasItemMeta()) {
            return false;
        }
        Byte by = (Byte)itemStack.getItemMeta().getPersistentDataContainer().get(this.santaBallKey, PersistentDataType.BYTE);
        return by != null && by == 1;
    }

    private ItemStack createTexturedHead(String url, String name) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        meta.setDisplayName(color(name));
        String value = SantaTextures.value(url);
        var profile = Bukkit.createProfile(UUID.nameUUIDFromBytes(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)), "SantaBall");
        profile.setProperty(new com.destroystokyo.paper.profile.ProfileProperty("textures", value));
        meta.setPlayerProfile(profile);
        item.setItemMeta(meta);
        return item;
    }

    private void faceLocation(ArmorStand armorStand, Location location) {
        Location location2 = armorStand.getLocation();
        Vector vector = location.toVector().subtract(location2.toVector());
        if (vector.lengthSquared() < 1.0E-4) {
            return;
        }
        Location location3 = location2.clone();
        location3.setDirection(vector);
        armorStand.teleport(location3);
    }

    private void removeTemporary(Entity entity) {
        if (entity == null) {
            return;
        }
        this.temporaryEntities.remove(entity);
        this.giftRewards.remove(entity.getUniqueId());
        entity.remove();
    }

    private void playConfiguredSound(World world, Location location, String string, Sound sound) {
        String string2 = this.getConfig().getString(string + ".sound", sound.name());
        Sound sound2 = sound;
        if (string2 != null) {
            try {
                sound2 = Sound.valueOf((String)string2.toUpperCase(Locale.ROOT));
            }
            catch (IllegalArgumentException illegalArgumentException) {
                this.getLogger().warning("Unknown sound in config: " + string2 + " (" + string + ")");
            }
        }
        float f = (float)this.d(string + ".volume", 1.0, 0.0, 10.0);
        float f2 = (float)this.d(string + ".pitch", 1.0, 0.01, 2.0);
        world.playSound(location, sound2, f, f2);
    }

    private double configuredTargetRange() {
        return this.d("santa.ability.target-range", 18.0, 1.0, 32.0);
    }

    private int calculateAbilityLifetimeTicks() {
        int n = this.i("santa.ability.santas.lifetime-ticks", 180, 40, 400);
        int n2 = this.i("santa.ability.santas.gifts.total", 60, 1, HARD_MAX_GIFTS);
        int n3 = this.i("santa.ability.santas.gifts.interval-ticks", 2, 1, 20);
        int n4 = this.i("santa.ability.santas.gifts.lifetime-ticks", 100, 20, 300);
        int stagger = this.i("santa.ability.avalanche.stagger-ticks-max", 6, 0, 20);
        int fall = this.i("santa.ability.avalanche.fall-duration-ticks", 24, 10, 60);
        int storm = this.i("santa.ability.avalanche.storm.duration-ticks", 60, 10, 200);
        int n5 = Math.max(stagger + fall + 20, storm + 10);
        return Math.max(n, Math.max(n2 * n3 + n4, n5)) + 10;
    }

    private void loadCooldowns() { cooldowns.putAll(new SantaCooldownStorage(host).load()); }
    private void saveCooldownsNow() { saveCooldownSnapshot(new HashMap<>(cooldowns)); }
    private void saveCooldownSnapshot(Map<UUID, Long> snapshot) { new SantaCooldownStorage(host).save(snapshot); }

    private int secondsToTicks(double d) {
        return (int)Math.max(0L, Math.min(1200L, Math.round(d * 20.0)));
    }

    private int i(String string, int n, int n2, int n3) {
        return Math.max(n2, Math.min(n3, this.getConfig().getInt(string, n)));
    }

    private double d(String string, double d, double d2, double d3) {
        double d4 = this.getConfig().getDouble(string, d);
        if (!Double.isFinite(d4)) {
            d4 = d;
        }
        return Math.max(d2, Math.min(d3, d4));
    }

    private String cleanNumber(double d) {
        if (Math.rint(d) == d) {
            return String.valueOf((long)d);
        }
        return String.valueOf(d);
    }

    private String color(String string) {
        return ChatColor.translateAlternateColorCodes((char)'&', (String)(string == null ? "" : string));
    }
}

