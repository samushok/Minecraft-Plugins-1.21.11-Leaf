package me.saminasian.santaball;

import com.google.common.collect.ImmutableMultimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import java.util.*;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** Packet-only player models for Mojang-mapped 1.21.11. No NPC AI or world players. */
public final class SantaNpcOverlay {
 private static final Set<Group> ACTIVE=new HashSet<>();
 private static boolean warned;
 private SantaNpcOverlay(){}
 public static void apply(JavaPlugin plugin,List<ArmorStand> anchors,Player owner,Player target){
  if(anchors.isEmpty()||!plugin.getConfig().getBoolean("ability.santas.player-npcs.enabled",true))return;
  Group group=null;
  try{
   group=new Group(plugin,anchors,owner,target);ACTIVE.add(group);group.tick();
   if(!group.cleaned){Group created=group;group.task=Bukkit.getScheduler().runTaskTimer(plugin,created::tick,5,5);}
  }catch(RuntimeException|LinkageError error){
   if(group!=null)group.cleanup();
   if(!warned){warned=true;plugin.getLogger().warning("Santa NPC unavailable, using equipped armor stands: "+error);}
  }
 }
 public static void shutdown(){for(Group group:List.copyOf(ACTIVE))group.cleanup();}
 static FakeSanta create(Location location,Location lookAt,String texture){
  UUID uuid=UUID.randomUUID();String name="SB"+uuid.toString().replace("-","").substring(0,14);
  GameProfile profile=new GameProfile(uuid,name,new PropertyMap(ImmutableMultimap.of("textures",new Property("textures",SantaTextures.value(texture)))));
  int id=net.minecraft.world.entity.Entity.nextEntityId();Location facing=location.clone();
  var direction=lookAt.toVector().subtract(location.clone().add(0,1.62,0).toVector());
  if(direction.lengthSquared()>0.0001)facing.setDirection(direction);
  var entry=new ClientboundPlayerInfoUpdatePacket.Entry(uuid,profile,false,0,GameType.SURVIVAL,null,true,0,null);
  var info=new ClientboundPlayerInfoUpdatePacket(EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED),entry);
  var spawn=new ClientboundAddEntityPacket(id,uuid,location.getX(),location.getY(),location.getZ(),facing.getPitch(),facing.getYaw(),EntityType.PLAYER,0,Vec3.ZERO,facing.getYaw());
  var metadata=new ClientboundSetEntityDataPacket(id,List.of(SynchedEntityData.DataValue.create(Avatar.DATA_PLAYER_MODE_CUSTOMISATION,(byte)0x7f)));
  return new FakeSanta(id,uuid,name,List.of(info,spawn,metadata));
 }
 record FakeSanta(int id,UUID uuid,String name,List<Packet<?>> packets){}
 private static void send(Player player,Packet<?> packet){((CraftPlayer)player).getHandle().connection.send(packet);}
 private static final class Group{
  final JavaPlugin plugin;final List<ArmorStand> anchors;final List<FakeSanta> fakes=new ArrayList<>();
  final Set<UUID> viewers=new HashSet<>();final Location center;final double distanceSquared;final int lifetime;
  final PlayerTeam team;final Packet<?> teamAdd,teamRemove,destroy,profilesRemove;
  BukkitTask task;int elapsed;boolean cleaned;
  Group(JavaPlugin plugin,List<ArmorStand> anchors,Player owner,Player target){
   this.plugin=plugin;this.anchors=List.copyOf(anchors);center=owner.getLocation().clone();
   double distance=plugin.getConfig().getDouble("ability.santas.player-npcs.view-distance",32);
   distance=Double.isFinite(distance)?Math.clamp(distance,8,48):32;distanceSquared=distance*distance;
   lifetime=Math.clamp(plugin.getConfig().getInt("ability.santas.lifetime-ticks",90),20,160);
   team=new PlayerTeam(new Scoreboard(),"sb"+UUID.randomUUID().toString().replace("-","").substring(0,14));
   team.setNameTagVisibility(Team.Visibility.NEVER);team.setCollisionRule(Team.CollisionRule.NEVER);
   String texture=plugin.getConfig().getString("ability.santas.player-npcs.skin-texture-url",SantaTextures.DEFAULT);
   boolean face=plugin.getConfig().getBoolean("ability.santas.face-target",true);
   for(ArmorStand anchor:anchors){if(!anchor.isValid())continue;
    Location look=face?target.getEyeLocation():anchor.getLocation().add(anchor.getLocation().getDirection().multiply(5)).add(0,1.62,0);
    FakeSanta fake=create(anchor.getLocation(),look,texture);fakes.add(fake);team.getPlayers().add(fake.name());
   }
   teamAdd=ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(team,true);teamRemove=ClientboundSetPlayerTeamPacket.createRemovePacket(team);
   destroy=new ClientboundRemoveEntitiesPacket(fakes.stream().mapToInt(FakeSanta::id).toArray());
   profilesRemove=new ClientboundPlayerInfoRemovePacket(fakes.stream().map(FakeSanta::uuid).toList());
  }
  void tick(){
   if(cleaned)return;if(elapsed>=lifetime||anchors.stream().noneMatch(ArmorStand::isValid)){cleanup();return;}
   Set<UUID> nearby=new HashSet<>();
   for(Player player:center.getWorld().getPlayers()){
    if(!player.isOnline()||player.getLocation().distanceSquared(center)>distanceSquared)continue;
    UUID id=player.getUniqueId();nearby.add(id);if(viewers.contains(id))continue;
    try{
     viewers.add(id);send(player,teamAdd);
     for(FakeSanta fake:fakes)for(Packet<?> packet:fake.packets())send(player,packet);
     for(ArmorStand anchor:anchors)if(anchor.isValid())player.hideEntity(plugin,anchor);
    }catch(RuntimeException error){
     try{removeViewer(id);}catch(RuntimeException ignored){}
     if(!warned){warned=true;plugin.getLogger().warning("Santa NPC send failed: "+error);}
    }
   }
   for(UUID id:Set.copyOf(viewers))if(!nearby.contains(id)){
    try{removeViewer(id);}catch(RuntimeException ignored){}
   }
   elapsed+=5;
  }
  void removeViewer(UUID id){
   viewers.remove(id);Player player=Bukkit.getPlayer(id);if(player==null||!player.isOnline())return;
   try{send(player,destroy);send(player,profilesRemove);send(player,teamRemove);}
   finally{for(ArmorStand anchor:anchors)if(anchor.isValid())player.showEntity(plugin,anchor);}
  }
  void cleanup(){
   if(cleaned)return;cleaned=true;if(task!=null)task.cancel();
   for(UUID id:Set.copyOf(viewers))try{removeViewer(id);}catch(RuntimeException ignored){}
   ACTIVE.remove(this);
  }
 }
}
