package me.saminasian.santaball;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import me.saminasian.santaball.SantaNpcOverlay;
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

public final class SantaBallPlugin
extends JavaPlugin
implements Listener {
    private static final double HARD_MAX_TARGET_RANGE = 32.0;
    private static final int HARD_MAX_SANTAS = 8;
    private static final int HARD_MAX_GIFTS = 32;
    private NamespacedKey santaBallKey;
    private final Map<UUID, Long> cooldowns = new HashMap<UUID, Long>();
    private final Set<Entity> temporaryEntities = new HashSet<Entity>();
    private final Random random = new Random();
    private int activeAbilities = 0;

    public void onEnable() {
        this.saveDefaultConfig();
        this.santaBallKey = new NamespacedKey((Plugin)this, "santa_ball");
        if (!this.getDataFolder().exists() && !this.getDataFolder().mkdirs()) {
            this.getLogger().warning("Could not create plugin data folder.");
        }
        this.loadCooldowns();
        Bukkit.getPluginManager().registerEvents((Listener)this, (Plugin)this);
        this.getLogger().info("Santaball 1.4 enabled for Leaf / Paper 1.21.11");
    }

    public void onDisable() {
        Bukkit.getScheduler().cancelTasks(this);
        this.saveCooldownSnapshot(new HashMap<UUID, Long>(this.cooldowns));
        for (Entity entity : new ArrayList<Entity>(this.temporaryEntities)) {
            if (entity == null) continue;
            entity.remove();
        }
        this.temporaryEntities.clear();
        this.activeAbilities = 0;
        SantaNpcOverlay.shutdown();
    }

    public boolean onCommand(CommandSender commandSender, Command command, String string, String[] stringArray) {
        Player player;
        if (!command.getName().equalsIgnoreCase("santaball")) {
            return false;
        }
        if (stringArray.length > 0 && stringArray[0].equalsIgnoreCase("reload")) {
            if (!commandSender.hasPermission("santaball.reload")) {
                commandSender.sendMessage(this.color(this.getConfig().getString("messages.no-permission", "&cУ вас нет прав.")));
                return true;
            }
            this.reloadConfig();
            commandSender.sendMessage(this.color(this.getConfig().getString("messages.reloaded", "&aSantaBall config перезагружен.")));
            return true;
        }
        if (!commandSender.hasPermission("santaball.give")) {
            commandSender.sendMessage(this.color(this.getConfig().getString("messages.no-permission", "&cУ вас нет прав.")));
            return true;
        }
        Player player2 = null;
        if (stringArray.length == 0) {
            if (commandSender instanceof Player) {
                player2 = player = (Player)commandSender;
            }
        } else if (stringArray[0].equalsIgnoreCase("give")) {
            if (stringArray.length >= 2) {
                player2 = Bukkit.getPlayerExact((String)stringArray[1]);
                if (player2 == null) {
                    player2 = Bukkit.getPlayer((String)stringArray[1]);
                }
            } else if (commandSender instanceof Player) {
                player2 = player = (Player)commandSender;
            }
        } else {
            player2 = Bukkit.getPlayerExact((String)stringArray[0]);
            if (player2 == null) {
                player2 = Bukkit.getPlayer((String)stringArray[0]);
            }
        }
        if (player2 == null) {
            commandSender.sendMessage(this.color("&eИспользование: &f/santaball give [ник] &7или &f/santaball reload"));
            return true;
        }
        ItemStack ball = this.createSantaBall();
        Map<Integer, ItemStack> map = player2.getInventory().addItem(ball);
        for (ItemStack itemStack : map.values()) {
            player2.getWorld().dropItemNaturally(player2.getLocation(), itemStack);
        }
        String string2 = this.getConfig().getString("messages.given", "&aSantaBall выдан игроку &f%player%&a.");
        commandSender.sendMessage(this.color(string2.replace("%player%", player2.getName())));
        return true;
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
            player.sendMessage(this.color(this.getConfig().getString("ability.no-target-message", "&cРядом нет игрока для способности.")));
            return;
        }
        int n = this.i("performance.max-concurrent-abilities", 4, 1, 8);
        if (this.activeAbilities >= n) {
            player.sendMessage(this.color(this.getConfig().getString("messages.busy", "&eСлишком много новогодних эффектов одновременно. Попробуйте через пару секунд.")));
            return;
        }
        Set<Entity> existingEntities = new HashSet<>(this.temporaryEntities);
        ++this.activeAbilities;
        this.startCooldown(player);
        try {
            this.activate(player, player2);
            int n2 = this.calculateAbilityLifetimeTicks();
            Bukkit.getScheduler().runTaskLater((Plugin)this, () -> {
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
            player.sendMessage(this.color(this.getConfig().getString("messages.failed", "&cСпособность не смогла запуститься. Кулдаун не потрачен.")));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onHopper(org.bukkit.event.inventory.InventoryPickupItemEvent event) {
        if (temporaryEntities.contains(event.getItem())) event.setCancelled(true);
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
        this.playConfiguredSound(world, player.getLocation(), "ability.sounds.start", Sound.ENTITY_PLAYER_LEVELUP);
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
        long l2 = (long)this.i("ability.cooldown-seconds", 300, 1, 86400) * 1000L;
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
        String string = this.getConfig().getString("ability.name", "Рождественская Лавина");
        String string2 = this.getConfig().getString("ability.cooldown-message", "&6Задержка: &f%ability% будет доступна через &c%time% сек.");
        player.sendMessage(this.color(string2.replace("%ability%", string).replace("%time%", String.valueOf(l))));
    }

    private void startCooldown(Player player) {
        this.cooldowns.put(player.getUniqueId(), System.currentTimeMillis());
        this.saveCooldownsNow();
    }

    private List<ArmorStand> spawnSantas(Player player, Player player2) {
        final ArrayList<ArmorStand> arrayList = new ArrayList<ArmorStand>();
        if (!this.getConfig().getBoolean("ability.santas.enabled", true)) {
            return arrayList;
        }
        int n = this.i("ability.santas.count", 6, 1, 8);
        double d = this.d("ability.santas.ring-radius", 4.5, 1.5, 7.0);
        int n2 = this.i("ability.santas.lifetime-ticks", 90, 20, 160);
        Location location = player.getLocation().clone();
        World world = location.getWorld();
        if (world == null) {
            return arrayList;
        }
        ItemStack itemStack = this.createTexturedHead(this.getConfig().getString("item.santa-head-texture-url", "http://textures.minecraft.net/texture/8a159236d7512bdb4326a24e14502167b76bcd85c041931c2194201b17f5e7"), "&cSanta");
        for (int i = 0; i < n; ++i) {
            double d2 = Math.PI * 2 * (double)i / (double)n;
            Location location2 = location.clone().add(Math.cos(d2) * d, 0.1, Math.sin(d2) * d);
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
            if (this.getConfig().getBoolean("ability.santas.face-target", true)) {
                this.faceLocation(armorStand2, player2.getEyeLocation());
            }
            this.temporaryEntities.add((Entity)armorStand2);
            arrayList.add(armorStand2);
        }
        new BukkitRunnable(){

            public void run() {
                for (ArmorStand armorStand : arrayList) {
                    SantaBallPlugin.this.removeTemporary((Entity)armorStand);
                }
            }
        }.runTaskLater((Plugin)this, (long)n2);
        SantaNpcOverlay.apply(this, arrayList, player, player2);
        return arrayList;
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
        if (list.isEmpty() || !this.getConfig().getBoolean("ability.santas.particles.enabled", true)) {
            return;
        }
        final int n = this.i("ability.santas.lifetime-ticks", 90, 20, 160);
        final int n2 = this.i("ability.santas.particles.refresh-ticks", 5, 2, 20);
        final int n3 = this.i("ability.santas.particles.red-count-per-santa", 2, 0, 8);
        final int n4 = this.i("ability.santas.particles.green-count-per-santa", 1, 0, 8);
        final int n5 = this.i("ability.santas.particles.snow-count-per-santa", 1, 0, 4);
        final double d = this.d("ability.santas.particles.spread", 0.3, 0.0, 1.25);
        float f = (float)this.d("ability.santas.particles.size", 1.15, 0.1, 3.0);
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
                    if (n5 <= 0) continue;
                    world.spawnParticle(Particle.SNOWFLAKE, location, n5, d, 0.4, d, 0.02);
                }
                this.lived += n2;
            }
        }.runTaskTimer((Plugin)this, 0L, (long)n2);
    }

    private void startGiftThrowing(final List<ArmorStand> list) {
        if (list.isEmpty() || !this.getConfig().getBoolean("ability.santas.gifts.enabled", true)) {
            return;
        }
        final int n = this.i("ability.santas.gifts.total", 12, 1, 32);
        int n2 = this.i("ability.santas.gifts.interval-ticks", 5, 2, 20);
        final int n3 = this.i("ability.santas.gifts.lifetime-ticks", 34, 10, 100);
        final double d = this.d("ability.santas.gifts.launch-horizontal", 0.32, 0.0, 2.0);
        final double d2 = this.d("ability.santas.gifts.launch-up", 0.42, 0.0, 2.0);
        String string = this.getConfig().getString("ability.santas.gifts.textures.red", "http://textures.minecraft.net/texture/b73a2114136b8ee4926caa51785414036a2b76e4f1668cb89d99716c421");
        String string2 = this.getConfig().getString("ability.santas.gifts.textures.green", "http://textures.minecraft.net/texture/884b8a32bc6de2884ba31398d5b028d1a4fa77f59a415b7e6f62f2623f4f6");
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
                ArmorStand armorStand = list2.get(SantaBallPlugin.this.random.nextInt(list2.size()));
                ItemStack itemStack3 = SantaBallPlugin.this.random.nextBoolean() ? itemStack.clone() : itemStack2.clone();
                Location location = armorStand.getLocation().add(0.0, 1.1, 0.0);
                World world = location.getWorld();
                if (world == null) {
                    return;
                }
                final Item item = world.dropItem(location, itemStack3);
                item.setPickupDelay(Integer.MAX_VALUE);
                item.setPersistent(false);
                item.setCanMobPickup(false);
                item.setCanPlayerPickup(false);
                item.setWillAge(false);
                item.setVelocity(new Vector((SantaBallPlugin.this.random.nextDouble() - 0.5) * d * 2.0, d2 + SantaBallPlugin.this.random.nextDouble() * 0.15, (SantaBallPlugin.this.random.nextDouble() - 0.5) * d * 2.0));
                SantaBallPlugin.this.temporaryEntities.add((Entity)item);
                SantaBallPlugin.this.playConfiguredSound(world, location, "ability.sounds.gift", Sound.ENTITY_ITEM_PICKUP);
                new BukkitRunnable(){

                    public void run() {
                        SantaBallPlugin.this.removeTemporary((Entity)item);
                     }
                }.runTaskLater((Plugin)SantaBallPlugin.this, (long)n3);
                ++this.spawned;
            }
        }.runTaskTimer((Plugin)this, 0L, (long)n2);
    }

    private void startAvalanche(final Player player, final Player player2) {
        if (!this.getConfig().getBoolean("ability.avalanche.enabled", true)) {
            return;
        }
        final int n = this.i("ability.avalanche.fall-duration-ticks", 24, 10, 60);
        final int n2 = this.i("ability.avalanche.update-interval-ticks", 2, 1, 4);
        final int n3 = this.i("ability.avalanche.track-target-ticks", 10, 0, n);
        final double d = this.d("ability.avalanche.spawn-height", 14.0, 6.0, 30.0);
        final int n4 = this.i("ability.avalanche.sphere-radius-blocks", 2, 1, 2);
        final Location location = player2.getLocation().clone();
        final World world = location.getWorld();
        if (world == null) {
            return;
        }
        final List<Vector> list = this.makeSnowSphereOffsets(n4);
        final ArrayList<BlockDisplay> arrayList = new ArrayList<BlockDisplay>();
        BlockData blockData = Material.SNOW_BLOCK.createBlockData();
        final Location location2 = location.clone().add(0.0, d, 0.0);
        for (Vector vector : list) {
            Location location3 = location2.clone().add(vector);
            BlockDisplay blockDisplay2 = (BlockDisplay)world.spawn(location3, BlockDisplay.class, blockDisplay -> {
                blockDisplay.setBlock(blockData);
                blockDisplay.setTeleportDuration(n2);
                blockDisplay.setInvulnerable(true);
                blockDisplay.setPersistent(false);
                blockDisplay.setGlowing(true);
                blockDisplay.setGlowColorOverride(Color.WHITE);
            });
            arrayList.add(blockDisplay2);
            this.temporaryEntities.add((Entity)blockDisplay2);
        }
        this.playConfiguredSound(world, location2, "ability.sounds.falling", Sound.ENTITY_SNOWBALL_THROW);
        new BukkitRunnable(){
            int elapsed = 0;
            double centerX = location2.getX();
            double centerZ = location2.getZ();

            public void run() {
                if (!player.isOnline() || player.isDead() || player.getWorld() != world || !player2.isOnline() || player2.isDead() || player2.getWorld() != world) {
                    arrayList.forEach(SantaBallPlugin.this::removeTemporary);
                    this.cancel();
                    return;
                }
                if (this.elapsed >= n) {
                    SantaBallPlugin.this.impact(player, new Location(world, this.centerX, location.getY(), this.centerZ), arrayList);
                    this.cancel();
                    return;
                }
                Location location4 = player2.getLocation();
                if (this.elapsed < n3) {
                    this.centerX += (location4.getX() - this.centerX) * 0.42;
                    this.centerZ += (location4.getZ() - this.centerZ) * 0.42;
                }
                double d4 = Math.min(1.0, (double)(this.elapsed + n2) / (double)n);
                double d2 = d4 * d4;
                double d3 = location.getY() + d * (1.0 - d2);
                Location location22 = new Location(world, this.centerX, d3, this.centerZ);
                for (int i = 0; i < arrayList.size(); ++i) {
                    BlockDisplay blockDisplay = (BlockDisplay)arrayList.get(i);
                    if (!blockDisplay.isValid()) continue;
                    blockDisplay.teleport(location22.clone().add((Vector)list.get(i)));
                }
                world.spawnParticle(Particle.SNOWFLAKE, location22, 12, (double)n4 * 0.65, (double)n4 * 0.65, (double)n4 * 0.65, 0.06);
                if (this.elapsed % 6 == 0) {
                    world.spawnParticle(Particle.CLOUD, location22, 3, (double)n4 * 0.3, (double)n4 * 0.3, (double)n4 * 0.3, 0.01);
                }
                this.elapsed += n2;
            }
        }.runTaskTimer((Plugin)this, 0L, (long)n2);
    }

    private List<Vector> makeSnowSphereOffsets(int n) {
        ArrayList<Vector> arrayList = new ArrayList<Vector>();
        arrayList.add(new Vector(0.0, 0.0, 0.0));
        double d = n == 1 ? 1.0 : 1.35;
        arrayList.add(new Vector(d, 0.0, 0.0));
        arrayList.add(new Vector(-d, 0.0, 0.0));
        arrayList.add(new Vector(0.0, d, 0.0));
        arrayList.add(new Vector(0.0, -d, 0.0));
        arrayList.add(new Vector(0.0, 0.0, d));
        arrayList.add(new Vector(0.0, 0.0, -d));
        if (n >= 2) {
            int[] nArray;
            double d2 = 0.95;
            for (int n2 : nArray = new int[]{-1, 1}) {
                for (int n3 : nArray) {
                    arrayList.add(new Vector((double)n2 * d2, (double)n3 * d2, 0.0));
                    arrayList.add(new Vector((double)n2 * d2, 0.0, (double)n3 * d2));
                    arrayList.add(new Vector(0.0, (double)n2 * d2, (double)n3 * d2));
                }
            }
        }
        return arrayList;
    }

    private void impact(Player player, Location location, List<BlockDisplay> list) {
        for (BlockDisplay blockDisplay : list) {
            this.removeTemporary((Entity)blockDisplay);
        }
        World world = location.getWorld();
        if (world == null) {
            return;
        }
        this.playConfiguredSound(world, location, "ability.sounds.impact", Sound.ENTITY_GENERIC_EXPLODE);
        world.spawnParticle(Particle.SNOWFLAKE, location.clone().add(0.0, 1.0, 0.0), 60, 2.0, 1.15, 2.0, 0.12);
        world.spawnParticle(Particle.CLOUD, location.clone().add(0.0, 0.7, 0.0), 18, 1.6, 0.7, 1.6, 0.08);
        world.spawnParticle(Particle.BLOCK, location.clone().add(0.0, 0.6, 0.0), 24, 1.4, 0.75, 1.4, 0.08, (Object)Material.SNOW_BLOCK.createBlockData());
        double d = this.d("ability.avalanche.impact-radius", 3.5, 0.5, 6.0);
        double d2 = this.d("ability.avalanche.damage-hearts", 4.0, 0.0, 20.0) * 2.0;
        double d3 = this.d("ability.avalanche.knockback-horizontal", 0.75, 0.0, 3.0);
        double d4 = this.d("ability.avalanche.knockback-up", 0.45, 0.0, 2.0);
        boolean bl = this.getConfig().getBoolean("ability.avalanche.slowness.enabled", true);
        int n = this.secondsToTicks(this.d("ability.avalanche.slowness.seconds", 2.5, 0.0, 20.0));
        int n2 = this.i("ability.avalanche.slowness.level", 2, 1, 10);
        for (Entity entity : world.getNearbyEntities(location, d, d, d)) {
            Player player2;
            if (!world.getPVP() || !(entity instanceof Player) || (player2 = (Player)entity).isDead() || player2.isInvulnerable() || player2.getGameMode() == GameMode.SPECTATOR || player2.getGameMode() == GameMode.CREATIVE || !player.canSee(player2) || entity.getUniqueId().equals(player.getUniqueId()) || entity.getLocation().distanceSquared(location) > d * d) continue;
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

    private ItemStack createSantaBall() {
        ItemStack itemStack = this.createTexturedHead(this.getConfig().getString("item.santa-head-texture-url", "http://textures.minecraft.net/texture/8a159236d7512bdb4326a24e14502167b76bcd85c041931c2194201b17f5e7"), this.getConfig().getString("item.name", "&c&lШАР &f❄ &cSANTA"));
        ItemMeta itemMeta = itemStack.getItemMeta();
        if (itemMeta == null) {
            return itemStack;
        }
        double d = this.configuredTargetRange();
        int n = this.i("ability.cooldown-seconds", 300, 1, 86400);
        ArrayList<String> arrayList = new ArrayList<String>();
        for (String string : this.getConfig().getStringList("item.lore")) {
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
        return this.d("ability.target-range", 18.0, 1.0, 32.0);
    }

    private int calculateAbilityLifetimeTicks() {
        int n = this.i("ability.santas.lifetime-ticks", 90, 20, 160);
        int n2 = this.i("ability.santas.gifts.total", 12, 1, 32);
        int n3 = this.i("ability.santas.gifts.interval-ticks", 5, 2, 20);
        int n4 = this.i("ability.santas.gifts.lifetime-ticks", 34, 10, 100);
        int n5 = this.i("ability.avalanche.fall-duration-ticks", 24, 10, 60) + 20;
        return Math.max(n, Math.max(n2 * n3 + n4, n5)) + 10;
    }

    private File cooldownFile() {
        return new File(this.getDataFolder(), "cooldowns.properties");
    }

    private void loadCooldowns() {
        if (!this.getConfig().getBoolean("performance.persist-cooldowns", true)) {
            return;
        }
        File file = this.cooldownFile();
        if (!file.isFile()) {
            return;
        }
        Properties properties = new Properties();
        try (FileInputStream fileInputStream = new FileInputStream(file);){
            properties.load(fileInputStream);
            long l = System.currentTimeMillis();
            long l2 = (long)this.i("ability.cooldown-seconds", 300, 1, 86400) * 1000L;
            for (String string : properties.stringPropertyNames()) {
                try {
                    UUID uUID = UUID.fromString(string);
                    long l3 = Long.parseLong(properties.getProperty(string));
                    if (l - l3 >= l2) continue;
                    this.cooldowns.put(uUID, l3);
                }
                catch (IllegalArgumentException illegalArgumentException) {}
            }
        }
        catch (IOException iOException) {
            this.getLogger().warning("Could not load cooldowns: " + iOException.getMessage());
        }
    }

    private void saveCooldownsNow() {
        if (!this.getConfig().getBoolean("performance.persist-cooldowns", true)) {
            return;
        }
        this.saveCooldownSnapshot(new HashMap<UUID, Long>(this.cooldowns));
    }

    private void saveCooldownSnapshot(Map<UUID, Long> map) {
        if (!this.getConfig().getBoolean("performance.persist-cooldowns", true)) {
            return;
        }
        Properties properties = new Properties();
        for (Map.Entry<UUID, Long> object : map.entrySet()) {
            properties.setProperty(object.getKey().toString(), Long.toString(object.getValue()));
        }
        File file = this.cooldownFile();
        try {
            File iOException = file.getParentFile();
            if (iOException != null && !iOException.exists() && !iOException.mkdirs()) {
                throw new IOException("Could not create data directory");
            }
            java.nio.file.Path temporary = file.toPath().resolveSibling("cooldowns.properties.tmp");
            try (var stream = java.nio.file.Files.newOutputStream(temporary)) {
                properties.store(stream, "Santaball cooldowns - generated automatically");
            }
            try {
                java.nio.file.Files.move(temporary, file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                java.nio.file.Files.move(temporary, file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
        catch (IOException iOException) {
            this.getLogger().warning("Could not save cooldowns: " + iOException.getMessage());
        }
    }

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
