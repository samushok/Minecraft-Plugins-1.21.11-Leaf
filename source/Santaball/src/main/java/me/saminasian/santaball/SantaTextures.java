package me.saminasian.santaball;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Pattern;
final class SantaTextures {
 static final String DEFAULT="http://textures.minecraft.net/texture/8a159236d7512bdb4326a24e14502167b76bcd85c041931c2194201b17f5e7";
 private static final Pattern URL=Pattern.compile("https?://textures\\.minecraft\\.net/texture/[0-9a-fA-F]{32,64}");
 private SantaTextures(){}
 static String value(String url){
  String safe=url==null?DEFAULT:url.trim();if(!URL.matcher(safe).matches())safe=DEFAULT;
  return Base64.getEncoder().encodeToString(("{\"textures\":{\"SKIN\":{\"url\":\""+safe+"\"}}}").getBytes(StandardCharsets.UTF_8));
 }
}
