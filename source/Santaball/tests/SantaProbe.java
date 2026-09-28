import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.*;
import java.lang.reflect.*;
import java.util.*;
import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.level.*;
import net.minecraft.server.network.*;

public class SantaProbe extends JavaPlugin implements Listener {
 JavaPlugin p; int passed; boolean cancel; World w; Player owner,target; final List<ServerPlayer> actors=new ArrayList<>();
 final List<Packet<?>> packets=new ArrayList<>();
 Object call(String name,Class<?>[] types,Object... args)throws Exception{Method m=p.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(p,args);}
 Object field(String name)throws Exception{Field f=p.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(p);}
 void ok(boolean value,String label){if(!value)throw new AssertionError(label);passed++;getLogger().info("PASS "+label);}
 void later(long ticks,Checked task){Bukkit.getScheduler().runTaskLater(this,()->{try{task.run();}catch(Throwable e){fail(e);}},ticks);}
 interface Checked{void run()throws Exception;}
 public void onEnable(){Bukkit.getPluginManager().registerEvents(this,this);later(40,()->{w=Bukkit.getWorlds().getFirst();for(int cx=-1;cx<=1;cx++)for(int cz=-1;cz<=1;cz++){w.getChunkAt(cx,cz).load();w.addPluginChunkTicket(cx,cz,this);}later(10,this::checks);});}
 @EventHandler(priority=EventPriority.HIGHEST) public void damage(EntityDamageByEntityEvent e){if(cancel)e.setCancelled(true);}
 void fail(Throwable e){e.printStackTrace();getLogger().severe("TEST_FAILED");finish();}
 @SuppressWarnings("unchecked") Player actor(String name,double x)throws Exception{
  var server=((CraftServer)Bukkit.getServer()).getServer();var level=((CraftWorld)w).getHandle();
  GameProfile profile=new GameProfile(UUID.randomUUID(),name);
  ServerPlayer n=new ServerPlayer(server,level,profile,ClientInformation.createDefault());
  Connection c=new Connection(PacketFlow.SERVERBOUND);c.channel=new EmbeddedChannel();
  n.connection=new ServerGamePacketListenerImpl(server,c,n,CommonListenerCookie.createInitial(profile,false)){
   @Override public boolean hasClientLoaded(){return true;}
   @Override public void send(Packet<?> packet){packets.add(packet);}
  };
  n.snapTo(x,100,0);n.setNoGravity(true);var pl=server.getPlayerList();
  Field f=net.minecraft.server.players.PlayerList.class.getDeclaredField("playersByUUID");f.setAccessible(true);((Map<UUID,ServerPlayer>)f.get(pl)).put(n.getUUID(),n);
  f=net.minecraft.server.players.PlayerList.class.getDeclaredField("playersByName");f.setAccessible(true);((Map<String,ServerPlayer>)f.get(pl)).put(name.toLowerCase(Locale.ROOT),n);
  pl.players.add(n);level.addNewPlayer(n);n.unsetRemoved();actors.add(n);
  Player b=n.getBukkitEntity();b.setInvulnerable(false);b.setGameMode(GameMode.SURVIVAL);b.addAttachment(this,"santaball.give",true);b.addAttachment(this,"santaball.reload",true);b.setHealth(20);getLogger().info("ACTOR "+name+" removal="+n.getRemovalReason()+" invul="+n.isInvulnerable());return b;
 }
 void checks()throws Exception{
  p=(JavaPlugin)Bukkit.getPluginManager().getPlugin("Santaball");ok(p!=null&&p.isEnabled(),"plugin enabled together with SummerBall");
  w=Bukkit.getWorlds().getFirst();w.setPVP(true);for(int cx=-1;cx<=1;cx++)for(int cz=-1;cz<=1;cz++){w.getChunkAt(cx,cz).load();w.addPluginChunkTicket(cx,cz,this);}owner=actor("SantaOwner",0);target=actor("SantaTarget",2);
  ok(owner.isOnline()&&target.isOnline(),"server-side test players registered");
  Bukkit.dispatchCommand(Bukkit.getConsoleSender(),"santaball give SantaOwner");ItemStack item=owner.getInventory().getItem(0);
  ok(item!=null&&item.getType()==Material.PLAYER_HEAD,"give command creates head");
  ok((boolean)call("isSantaBall",new Class[]{ItemStack.class},item),"existing PDC santa_ball marker");
  SkullMeta meta=(SkullMeta)item.getItemMeta();ok(meta.getPlayerProfile()!=null&&!meta.getPlayerProfile().getProperties().isEmpty(),"head texture property present");
  ok(!(boolean)call("isSantaBall",new Class[]{ItemStack.class},new ItemStack(Material.PLAYER_HEAD)),"ordinary head rejected");
  getLogger().info("TARGET state world="+w.getPlayers().size()+" mode="+target.getGameMode()+" dead="+target.isDead()+" invulnerable="+target.isInvulnerable()+" visible="+owner.canSee(target)+" pvp="+w.getPVP()+" distance="+owner.getLocation().distance(target.getLocation()));
  ok(target.equals(call("findNearestTarget",new Class[]{Player.class},owner)),"nearest target excludes owner");
  target.setGameMode(GameMode.CREATIVE);ok(call("findNearestTarget",new Class[]{Player.class},owner)==null,"creative target excluded");target.setGameMode(GameMode.SURVIVAL);
  w.setPVP(false);ok(call("findNearestTarget",new Class[]{Player.class},owner)==null,"PVP-disabled world blocked");w.setPVP(true);
  cancel=true;target.setNoDamageTicks(0);var velocity=target.getVelocity().clone();
  call("impact",new Class[]{Player.class,Location.class,List.class},owner,target.getLocation(),List.of());
  ok(target.getHealth()==20&&target.getVelocity().equals(velocity)&&!target.hasPotionEffect(PotionEffectType.SLOWNESS),"cancelled damage blocks damage, knockback and slowness");cancel=false;
  target.setNoDamageTicks(0);call("impact",new Class[]{Player.class,Location.class,List.class},owner,target.getLocation(),List.of());
  ok(target.getHealth()<20&&target.hasPotionEffect(PotionEffectType.SLOWNESS),"allowed impact damages and slows target");
  ok(owner.getHealth()==20&&!owner.hasPotionEffect(PotionEffectType.SLOWNESS),"owner immune to own impact");
  target.setHealth(20);target.setNoDamageTicks(0);target.removePotionEffect(PotionEffectType.SLOWNESS);target.setVelocity(new org.bukkit.util.Vector());
  packets.clear();owner.getInventory().setItemInOffHand(item);Bukkit.getPluginManager().callEvent(new PlayerToggleSneakEvent(owner,true));
  ok((long)call("remainingCooldownSeconds",new Class[]{Player.class},owner)>0,"SHIFT activates and starts cooldown");
  ok(((Set<?>)field("temporaryEntities")).size()==25,"6 Santa anchors and 19 snow displays");
  long npcs=packets.stream().filter(x->x instanceof ClientboundAddEntityPacket a&&a.getType()==net.minecraft.world.entity.EntityType.PLAYER).count();
  getLogger().info("NPC COUNT "+npcs+" packets="+packets.stream().map(x->x.getClass().getSimpleName()).toList());
  ok(npcs==12,"6 packet NPCs sent to each of 2 viewers");
  var info=packets.stream().filter(x->x instanceof ClientboundPlayerInfoUpdatePacket).map(x->(ClientboundPlayerInfoUpdatePacket)x).filter(x->!x.entries().isEmpty()&&x.entries().getFirst().profile()!=null&&x.entries().getFirst().profile().name().startsWith("SB")).findFirst().orElseThrow();
  ok(!info.entries().getFirst().listed()&&!info.entries().getFirst().profile().properties().isEmpty(),"NPC skin present and hidden from tab");
  for(Packet<?> packet:packets){
   var buf=new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),((CraftServer)Bukkit.getServer()).getServer().registryAccess());
   try{
    if(packet instanceof ClientboundAddEntityPacket a)ClientboundAddEntityPacket.STREAM_CODEC.encode(buf,a);
    else if(packet instanceof ClientboundPlayerInfoUpdatePacket a)ClientboundPlayerInfoUpdatePacket.STREAM_CODEC.encode(buf,a);
    else if(packet instanceof ClientboundSetEntityDataPacket a)ClientboundSetEntityDataPacket.STREAM_CODEC.encode(buf,a);
    else if(packet instanceof ClientboundSetPlayerTeamPacket a)ClientboundSetPlayerTeamPacket.STREAM_CODEC.encode(buf,a);
   }finally{buf.release();}
  }
  ok(true,"NPC packets serialize on 1.21.11");
  int before=((Set<?>)field("temporaryEntities")).size();Bukkit.getPluginManager().callEvent(new PlayerToggleSneakEvent(owner,true));
  ok(((Set<?>)field("temporaryEntities")).size()==before,"cooldown blocks repeated activation");
  later(4,()->{
   ok(((Set<?>)field("temporaryEntities")).stream().anyMatch(x->x instanceof Item),"gift loop creates temporary gifts");
   for(Object obj:(Set<?>)field("temporaryEntities"))if(obj instanceof Item gift){ok(!gift.canPlayerPickup()&&!gift.canMobPickup(),"gift cannot be picked up");break;}
   later(10,()->{target.teleport(new Location(w,15,100,0));target.setVelocity(new org.bukkit.util.Vector());target.setHealth(20);target.setNoDamageTicks(0);});
  });
  later(32,()->{ok(target.getHealth()==20,"escaped target not hit remotely by avalanche");});
  later(115,()->{
   ok(((Set<?>)field("temporaryEntities")).isEmpty(),"all effects removed at end of ability");
   Class<?> overlay=Class.forName("me.saminasian.santaball.SantaNpcOverlay",false,p.getClass().getClassLoader());Field a=overlay.getDeclaredField("ACTIVE");a.setAccessible(true);
   ok(((Set<?>)a.get(null)).isEmpty(),"NPC groups cleaned");
   call("activate",new Class[]{Player.class,Player.class},owner,target);ok(!((Set<?>)field("temporaryEntities")).isEmpty(),"second visual ability started");
   Bukkit.getPluginManager().disablePlugin(p);ok(((Set<?>)field("temporaryEntities")).isEmpty()&&((Set<?>)a.get(null)).isEmpty(),"disable removes all effects and NPCs");
   ((Map<?,?>)field("cooldowns")).clear();Bukkit.getPluginManager().enablePlugin(p);
   ok((long)call("remainingCooldownSeconds",new Class[]{Player.class},owner)>0,"cooldown survives disable/enable via file");
   Bukkit.dispatchCommand(Bukkit.getConsoleSender(),"santaball reload");ok(p.isEnabled(),"reload command succeeds");
   getLogger().info("TESTS_PASSED "+passed);finish();
  });
 }
 void finish(){
  try{var pl=((CraftServer)Bukkit.getServer()).getServer().getPlayerList();for(ServerPlayer n:actors){
   n.level().removePlayerImmediately(n,net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);pl.players.remove(n);
   for(String key:List.of("playersByUUID","playersByName")){Field f=net.minecraft.server.players.PlayerList.class.getDeclaredField(key);f.setAccessible(true);((Map<?,?>)f.get(pl)).values().remove(n);}
  }}catch(Exception e){e.printStackTrace();}Bukkit.shutdown();
 }
}
