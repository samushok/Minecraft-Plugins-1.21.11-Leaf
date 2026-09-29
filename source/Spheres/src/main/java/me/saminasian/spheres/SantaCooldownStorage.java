package me.saminasian.spheres;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Owns persistence and the one-way import. Never writes to the legacy directory. */
final class SantaCooldownStorage {
    private final SpheresPlugin host;
    SantaCooldownStorage(SpheresPlugin host) { this.host = host; }
    private Path target() { return host.getDataFolder().toPath().resolve("cooldowns.properties"); }
    private long duration() { return Math.clamp(host.getConfig().getInt("santa.ability.cooldown-seconds", 300), 1, 86400) * 1000L; }
    Map<UUID, Long> load() {
        Map<UUID, Long> result = new HashMap<>();
        if (!host.getConfig().getBoolean("santa.performance.persist-cooldowns", true)) return result;
        Path current = target();
        Path legacy = host.getDataFolder().toPath().getParent().resolve("Santaball/cooldowns.properties");
        boolean importing = !Files.exists(current) && Files.isRegularFile(legacy);
        Path source = importing ? legacy : current;
        if (!Files.isRegularFile(source)) return result;
        long now = System.currentTimeMillis(), timeout = duration();
        int rejected = 0;
        try (BufferedReader reader = Files.newBufferedReader(source, StandardCharsets.ISO_8859_1)) {
            // Entries written by both plugin versions are UUID=timestamp, one per line.
            // Parse separately so a damaged escape/value cannot discard other valid players.
            for (String line; (line = reader.readLine()) != null;) {
                if (line.isBlank() || line.stripLeading().startsWith("#") || line.stripLeading().startsWith("!")) continue;
                try {
                    Properties one = new Properties(); one.load(new StringReader(line));
                    for (String key : one.stringPropertyNames()) {
                        UUID id = UUID.fromString(key);
                        if (!id.toString().equalsIgnoreCase(key)) throw new IllegalArgumentException("Noncanonical UUID");
                        long start = Long.parseLong(one.getProperty(key).trim());
                        if (start < 0 || start > now + 60_000L) throw new IllegalArgumentException("Invalid timestamp");
                        if (now - start < timeout) result.put(id, start);
                    }
                } catch (IllegalArgumentException invalid) { rejected++; }
            }
        } catch (IOException | SecurityException error) {
            host.getLogger().warning("Could not read Santa cooldowns: " + error.getMessage());
            return result;
        }
        if (rejected > 0) host.getLogger().warning("Ignored " + rejected + " malformed Santa cooldown entries.");
        if (importing && save(result)) host.getLogger().info("Imported " + result.size() + " Santa cooldowns; legacy file retained.");
        return result;
    }
    boolean save(Map<UUID, Long> values) {
        if (!host.getConfig().getBoolean("santa.performance.persist-cooldowns", true)) return false;
        Path temporary = null;
        try {
            Files.createDirectories(target().getParent());
            temporary = Files.createTempFile(target().getParent(), "cooldowns-", ".tmp");
            Properties properties = new Properties();
            long now = System.currentTimeMillis(), timeout = duration();
            values.forEach((id, start) -> { if (start >= 0 && start <= now + 60_000L && now - start < timeout) properties.setProperty(id.toString(), Long.toString(start)); });
            try (OutputStream stream = Files.newOutputStream(temporary)) { properties.store(stream, "Spheres Santa cooldowns"); }
            try { Files.move(temporary, target(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, target(), StandardCopyOption.REPLACE_EXISTING); }
            return true;
        } catch (IOException | SecurityException error) {
            host.getLogger().warning("Could not save Santa cooldowns: " + error.getMessage());
            return false;
        } finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException | SecurityException ignored) {}
        }
    }
}
