import org.bukkit.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.entity.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.*;
import org.bukkit.attribute.*;
import org.bukkit.persistence.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.potion.*;
import java.lang.reflect.*;
import java.util.*;
public class Probe extends JavaPlugin implements Listener {
 JavaPlugin p; boolean cancel; int passed;
 Object call(String name,Class<?>[] types,Object... args) throws Exception {
  Method m=p.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(p,args);
 }
 void ok(boolean x,String msg) {if(!x)throw new AssertionError(msg);passed++;getLogger().info("PASS "+msg);}
 public void onEnable(){ Bukkit.getPluginManager().registerEvents(this,this);Bukkit.getScheduler().runTaskLater(this,()->{try{runChecks();}catch(Throwable e){e.printStackTrace();getLogger().severe("TEST_FAILED");Bukkit.shutdown();}},40); }
 @EventHandler(priority=EventPriority.HIGHEST) public void cancel(EntityDamageByEntityEvent e){if(cancel)e.setCancelled(true);}
 void runChecks() throws Exception {
  p=(JavaPlugin)Bukkit.getPluginManager().getPlugin("SummerBall");ok(p!=null&&p.isEnabled(),"plugin enabled");
  for(String kind:List.of("Summer","Shuller")) {
   ItemStack item=(ItemStack)call("create"+kind+"Ball",new Class<?>[0]);
   ok(item.getType()==Material.PLAYER_HEAD,"head "+kind);
   ok((boolean)call("is"+kind,new Class<?>[]{ItemStack.class},item),"PDC "+kind);
   SkullMeta meta=(SkullMeta)item.getItemMeta();
   ok(meta.getPlayerProfile()!=null&&!meta.getPlayerProfile().getProperties().isEmpty(),"texture "+kind);
   ok(meta.getAttributeModifiers(Attribute.ATTACK_DAMAGE).iterator().next().getSlotGroup()==EquipmentSlotGroup.OFFHAND,"offhand modifier "+kind);
  }
  World w=Bukkit.getWorlds().getFirst();Location loc=new Location(w,0,100,0);w.getChunkAt(loc).load();
  Zombie owner=w.spawn(loc,Zombie.class);owner.setAI(false);owner.setSilent(true);
  Zombie target=w.spawn(loc.clone().add(2,0,0),Zombie.class);target.setAI(false);target.setSilent(true);
  target.getAttribute(Attribute.ARMOR).setBaseValue(20);target.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE,200,4));
  target.setHealth(20);target.setNoDamageTicks(0);
  call("applyHeatDamage",new Class<?>[]{LivingEntity.class,LivingEntity.class,double.class,int.class},target,owner,5.0,120);
  ok(Math.abs(target.getHealth()-15)<0.001,"heat bypasses armor and resistance: 5 health");
  ok(target.getFireTicks()>0,"heat ignition");
  target.setHealth(20);target.setFireTicks(0);target.setNoDamageTicks(0);cancel=true;
  call("applyHeatDamage",new Class<?>[]{LivingEntity.class,LivingEntity.class,double.class,int.class},target,owner,5.0,120);
  ok(target.getHealth()==20&&target.getFireTicks()==0,"cancelled heat cannot damage or ignite");cancel=false;
  owner.remove();target.remove();
  call("createShullerIllusion",new Class<?>[]{Location.class},loc);
  Field f=p.getClass().getDeclaredField("shullerVisualEntities");f.setAccessible(true);
  ok(((Set<?>)f.get(p)).size()==7,"illusion entities created");
  call("summerShatter",new Class<?>[]{Location.class},loc);
  UUID id=UUID.randomUUID();
  Player proxy=(Player)Proxy.newProxyInstance(getClassLoader(),new Class<?>[]{Player.class},(o,m,a)->switch(m.getName()) {
   case "getUniqueId"->id;case "isOnline"->true;case "isDead"->false;case "getLocation"->loc.clone();case "getWorld"->w;
   case "hashCode"->id.hashCode();case "equals"->o==a[0];case "toString"->"TestPlayer";
   default->throw new UnsupportedOperationException(m.getName());
  });
  call("summerSpawnWave",new Class<?>[]{Player.class,Location.class,int.class},proxy,loc,7);
  Field mel=p.getClass().getDeclaredField("melons");mel.setAccessible(true);
  ok(((Map<?,?>)mel.get(p)).size()==7,"melon wave created");
  Bukkit.getScheduler().runTaskLater(this,()->{try{
   ok(((Set<?>)f.get(p)).isEmpty(),"illusion cleanup");
   Bukkit.getPluginManager().disablePlugin(p);
   ok(((Map<?,?>)mel.get(p)).isEmpty(),"melon cleanup on disable");
   getLogger().info("TESTS_PASSED "+passed);
  }catch(Throwable e){e.printStackTrace();getLogger().severe("TEST_FAILED");}finally{Bukkit.shutdown();}},90);
 }
}
