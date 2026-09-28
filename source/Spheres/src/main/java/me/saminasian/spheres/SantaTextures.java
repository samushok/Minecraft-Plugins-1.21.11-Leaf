package me.saminasian.spheres;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

final class SantaTextures {
    static final String DEFAULT =
            "http://textures.minecraft.net/texture/8a159236d7512bdb4326a24e14502167b76bcd85c041931c2194201b17f5e7";

    private SantaTextures() {}

    static String value(String url) {
        String safe = safeUrl(url);
        return Base64.getEncoder().encodeToString(
                ("{\"textures\":{\"SKIN\":{\"url\":\"" + safe + "\"}}}")
                        .getBytes(StandardCharsets.UTF_8)
        );
    }

    /**
     * We never download these URLs on the server. They are embedded in the
     * client profile texture property. Keep the accepted hosts narrow so a
     * config typo cannot turn this into an arbitrary URL distributor.
     */
    static String safeUrl(String url) {
        if (url == null || url.isBlank()) return DEFAULT;
        try {
            URI uri = URI.create(url.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null) return DEFAULT;
            boolean http = scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https");
            boolean allowedHost =
                    host.equalsIgnoreCase("textures.minecraft.net")
                    || host.equalsIgnoreCase("www.minecraftskins.com")
                    || host.equalsIgnoreCase("minecraftskins.com");
            return http && allowedHost ? uri.toString() : DEFAULT;
        } catch (IllegalArgumentException ignored) {
            return DEFAULT;
        }
    }
}
