package me.saminasian.serverguardian;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import me.saminasian.serverguardian.api.ServerHealthService;
import me.saminasian.serverguardian.api.ServerHealthService.LoadState;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

public final class ServerGuardianPlugin extends org.bukkit.plugin.java.JavaPlugin implements ServerHealthService {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final DateTimeFormatter REPORT_TS =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(
            new Snapshot(20.0, 0.0, 0.0, 0, 0, 0, 0, System.currentTimeMillis())
    );

    private LoadState automaticState = LoadState.NORMAL;
    private OverrideMode overrideMode = OverrideMode.AUTO;
    private int pressureBadSamples = 0;
    private int emergencyBadSamples = 0;
    private int goodSamples = 0;
    private long lastCleanupMillis = 0L;
    private BukkitTask monitorTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Bukkit.getServicesManager().register(ServerHealthService.class, this, this, ServicePriority.Normal);
        startMonitor();
        getLogger().info("ServerGuardian enabled for Leaf / Paper 1.21.11");
    }

    @Override
    public void onDisable() {
        if (monitorTask != null) {
            monitorTask.cancel();
            monitorTask = null;
        }
        Bukkit.getServicesManager().unregister(ServerHealthService.class, this);
    }

    private void startMonitor() {
        if (monitorTask != null) {
            monitorTask.cancel();
            monitorTask = null;
        }
        if (!getConfig().getBoolean("monitor.enabled", true)) {
            return;
        }

        long period = i("monitor.sample-interval-ticks", 100, 20, 1200);
        monitorTask = new BukkitRunnable() {
            @Override
            public void run() {
                sample();
            }
        }.runTaskTimer(this, period, period);
    }

    private void sample() {
        double[] tpsValues = Bukkit.getTPS();
        double tps = tpsValues.length > 0 ? Math.min(20.0, tpsValues[0]) : 20.0;
        double mspt = Math.max(0.0, Bukkit.getAverageTickTime());

        Runtime runtime = Runtime.getRuntime();
        long max = Math.max(1L, runtime.maxMemory());
        long used = runtime.totalMemory() - runtime.freeMemory();
        double memoryPercent = used * 100.0 / max;

        int entities = 0;
        int chunks = 0;
        for (World world : Bukkit.getWorlds()) {
            entities += world.getEntityCount();
            chunks += world.getChunkCount();
        }

        int pendingTasks = Bukkit.getScheduler().getPendingTasks().size();
        Snapshot next = new Snapshot(
                tps,
                mspt,
                memoryPercent,
                Bukkit.getOnlinePlayers().size(),
                entities,
                chunks,
                pendingTasks,
                System.currentTimeMillis()
        );
        snapshot.set(next);

        updateState(next);

        if (state() == LoadState.EMERGENCY) {
            maybeEmergencyCleanup();
        }
    }

    private void updateState(Snapshot s) {
        double pTps = d("monitor.pressure.tps-below", 18.5, 1.0, 20.0);
        double pMspt = d("monitor.pressure.mspt-above", 45.0, 1.0, 1000.0);
        int pSamples = i("monitor.pressure.consecutive-samples", 3, 1, 60);

        double eTps = d("monitor.emergency.tps-below", 16.0, 1.0, 20.0);
        double eMspt = d("monitor.emergency.mspt-above", 60.0, 1.0, 1000.0);
        int eSamples = i("monitor.emergency.consecutive-samples", 2, 1, 60);

        double rTps = d("monitor.recovery.tps-above", 19.2, 1.0, 20.0);
        double rMspt = d("monitor.recovery.mspt-below", 40.0, 1.0, 1000.0);
        int rSamples = i("monitor.recovery.consecutive-samples", 4, 1, 120);

        boolean pressureBad = s.tps1m() < pTps || s.mspt() > pMspt;
        boolean emergencyBad = s.tps1m() < eTps || s.mspt() > eMspt;
        boolean good = s.tps1m() >= rTps && s.mspt() <= rMspt;

        pressureBadSamples = pressureBad ? pressureBadSamples + 1 : 0;
        emergencyBadSamples = emergencyBad ? emergencyBadSamples + 1 : 0;
        goodSamples = good ? goodSamples + 1 : 0;

        LoadState old = automaticState;
        switch (automaticState) {
            case NORMAL -> {
                if (emergencyBadSamples >= eSamples) {
                    automaticState = LoadState.EMERGENCY;
                } else if (pressureBadSamples >= pSamples) {
                    automaticState = LoadState.PRESSURE;
                }
            }
            case PRESSURE -> {
                if (emergencyBadSamples >= eSamples) {
                    automaticState = LoadState.EMERGENCY;
                } else if (goodSamples >= rSamples) {
                    automaticState = LoadState.NORMAL;
                }
            }
            case EMERGENCY -> {
                if (goodSamples >= rSamples) {
                    automaticState = LoadState.PRESSURE;
                    goodSamples = 0;
                }
            }
        }

        if (old != automaticState) {
            onAutomaticStateChange(old, automaticState, s);
        }
    }

    private void onAutomaticStateChange(LoadState oldState, LoadState newState, Snapshot s) {
        String line = String.format(
                Locale.ROOT,
                "State %s -> %s | TPS %.2f | MSPT %.2f | memory %.1f%% | entities %d | chunks %d | tasks %d",
                oldState,
                newState,
                s.tps1m(),
                s.mspt(),
                s.memoryPercent(),
                s.entities(),
                s.chunks(),
                s.pendingTasks()
        );

        if (getConfig().getBoolean("notifications.console", true)) {
            getLogger().warning(line);
        }

        if (getConfig().getBoolean("notifications.ops", true)) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.isOp() || player.hasPermission("serverguardian.admin")) {
                    tell(player, prefix() + "&e" + line);
                }
            }
        }

        if (getConfig().getBoolean("diagnostics.save-state-change-reports", true)) {
            saveReportAsync("state-" + newState.name().toLowerCase(Locale.ROOT), s, topTaskOwners());
        }
    }

    private void maybeEmergencyCleanup() {
        if (!getConfig().getBoolean("mitigation.enabled", true)
                || !getConfig().getBoolean("mitigation.safe-item-cleanup.enabled", true)) {
            return;
        }

        long cooldown = i("mitigation.safe-item-cleanup.cooldown-seconds", 30, 5, 3600) * 1000L;
        long now = System.currentTimeMillis();
        if (now - lastCleanupMillis < cooldown) {
            return;
        }
        lastCleanupMillis = now;

        int removed = safeItemCleanup(false);
        if (removed > 0) {
            getLogger().warning("Emergency cleanup removed " + removed + " old plain dropped item entities.");
        }
    }

    private int safeItemCleanup(boolean manual) {
        int minAgeTicks = i("mitigation.safe-item-cleanup.min-age-seconds", 180, 10, 7200) * 20;
        int maxRemove = i("mitigation.safe-item-cleanup.max-remove-per-run", 200, 1, 5000);
        double keepRadius = d("mitigation.safe-item-cleanup.keep-near-player-radius", 6.0, 0.0, 64.0);
        double keepRadiusSq = keepRadius * keepRadius;
        boolean keepMeta = getConfig().getBoolean("mitigation.safe-item-cleanup.keep-items-with-meta", true);
        String protectedTag = getConfig().getString(
                "mitigation.safe-item-cleanup.protected-scoreboard-tag",
                "serverguardian-protected"
        );

        int removed = 0;
        outer:
        for (World world : Bukkit.getWorlds()) {
            List<Player> players = world.getPlayers();
            for (Item item : world.getEntitiesByClass(Item.class)) {
                if (removed >= maxRemove) {
                    break outer;
                }
                if (!item.isValid() || item.getTicksLived() < minAgeTicks) {
                    continue;
                }
                if (protectedTag != null && !protectedTag.isBlank()
                        && item.getScoreboardTags().contains(protectedTag)) {
                    continue;
                }
                if (keepMeta && item.getItemStack().hasItemMeta()) {
                    continue;
                }

                boolean nearPlayer = false;
                if (keepRadius > 0.0) {
                    for (Player player : players) {
                        if (player.getLocation().distanceSquared(item.getLocation()) <= keepRadiusSq) {
                            nearPlayer = true;
                            break;
                        }
                    }
                }
                if (nearPlayer) {
                    continue;
                }

                item.remove();
                removed++;
            }
        }

        if (manual) {
            lastCleanupMillis = System.currentTimeMillis();
        }
        return removed;
    }

    private Map<String, Integer> topTaskOwners() {
        Map<String, Integer> counts = new HashMap<>();
        for (BukkitTask task : Bukkit.getScheduler().getPendingTasks()) {
            String name = task.getOwner().getName();
            counts.merge(name, 1, Integer::sum);
        }
        return counts;
    }

    private void showStatus(CommandSender sender) {
        Snapshot s = snapshot.get();
        double memWarn = d("monitor.memory-warning-percent", 88.0, 1.0, 100.0);

        tell(sender, prefix() + "&fState: " + stateColor(state()) + state()
                + " &8(auto=" + automaticState + ", override=" + overrideMode + ")");
        tell(sender, prefix() + String.format(
                Locale.ROOT,
                "&fTPS: &a%.2f &7| MSPT: %s%.2f &7| Memory: %s%.1f%%",
                s.tps1m(),
                s.mspt() > 50.0 ? "&c" : "&a",
                s.mspt(),
                s.memoryPercent() >= memWarn ? "&c" : "&a",
                s.memoryPercent()
        ));
        tell(sender, prefix() + "&fPlayers: &b" + s.players()
                + " &7| Entities: &b" + s.entities()
                + " &7| Chunks: &b" + s.chunks()
                + " &7| Pending tasks: &b" + s.pendingTasks());
        tell(sender, prefix() + "&fCosmetic multiplier for cooperating plugins: &b"
                + clean(cosmeticMultiplier()));
    }

    private void showPlugins(CommandSender sender) {
        Map<String, Integer> counts = topTaskOwners();
        int limit = i("diagnostics.top-plugin-tasks", 12, 3, 100);

        tell(sender, prefix() + "&fPending Bukkit tasks by plugin &8(tasks != CPU time):");
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(limit)
                .forEach(entry -> tell(sender,
                        "&7- &f" + entry.getKey() + ": &b" + entry.getValue()));

        long disabled = List.of(Bukkit.getPluginManager().getPlugins()).stream()
                .filter(plugin -> !plugin.isEnabled())
                .count();
        tell(sender, prefix() + "&7Loaded plugins: &f"
                + Bukkit.getPluginManager().getPlugins().length
                + " &7| disabled: &f" + disabled);
    }

    private void showReport(CommandSender sender) {
        showStatus(sender);
        showPlugins(sender);
        for (World world : Bukkit.getWorlds()) {
            tell(sender, "&7- world &f" + world.getName()
                    + "&7: players=&b" + world.getPlayerCount()
                    + "&7 entities=&b" + world.getEntityCount()
                    + "&7 chunks=&b" + world.getChunkCount()
                    + "&7 blockEntities=&b" + world.getTileEntityCount()
                    + "&7 tickableBlockEntities=&b" + world.getTickableTileEntityCount());
        }
    }

    private void runSecurityScan(CommandSender sender) {
        if (!getConfig().getBoolean("security-scan.enabled", true)) {
            tell(sender, prefix() + "&cSecurity scan отключён в config.yml.");
            return;
        }

        final boolean showClean = getConfig().getBoolean("security-scan.show-clean-plugins", true);
        final int perEntry = i("security-scan.max-bytes-per-entry", 2_097_152, 32_768, 16_777_216);
        final int perJar = i("security-scan.max-bytes-per-jar", 33_554_432, 1_048_576, 268_435_456);
        final Plugin[] plugins = Bukkit.getPluginManager().getPlugins();

        tell(sender, prefix() + "&eRead-only JAR scan запущен асинхронно...");
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            List<JarScanResult> results = new ArrayList<>();
            for (Plugin plugin : plugins) {
                results.add(scanPlugin(plugin, perEntry, perJar));
            }
            results.sort(Comparator.comparingInt(JarScanResult::score).reversed());

            Bukkit.getScheduler().runTask(this, () -> {
                tell(sender, prefix() + "&fJAR scan: score — только сигнал для ручной проверки, не вердикт.");
                for (JarScanResult result : results) {
                    if (!showClean && result.score() == 0) {
                        continue;
                    }
                    String color = result.score() >= 7 ? "&c" : result.score() >= 3 ? "&e" : "&a";
                    tell(sender, "&7- &f" + result.pluginName()
                            + " " + color + "score=" + result.score()
                            + " &8sha256=" + result.shaPrefix()
                            + (result.signals().isEmpty() ? "" : " &7[" + String.join(", ", result.signals()) + "]"));
                }
            });
        });
    }

    private JarScanResult scanPlugin(Plugin plugin, int perEntry, int perJar) {
        try {
            URI uri = Objects.requireNonNull(plugin.getClass().getProtectionDomain().getCodeSource()).getLocation().toURI();
            Path source = Path.of(uri);
            if (!Files.isRegularFile(source) || !source.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                return new JarScanResult(plugin.getName(), 0, "not-a-jar", List.of("source-not-jar"));
            }

            String sha = sha256(source);

            SignalAccumulator acc = new SignalAccumulator();
            long consumed = 0L;
            try (InputStream raw = Files.newInputStream(source);
                 ZipInputStream zip = new ZipInputStream(raw)) {
                ZipEntry entry;
                byte[] buffer = new byte[8192];

                while ((entry = zip.getNextEntry()) != null && consumed < perJar) {
                    if (entry.isDirectory()) {
                        continue;
                    }

                    int remainingEntry = perEntry;
                    java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(
                            Math.min(perEntry, 65_536)
                    );
                    while (remainingEntry > 0 && consumed < perJar) {
                        int n = zip.read(buffer, 0, Math.min(buffer.length, remainingEntry));
                        if (n < 0) {
                            break;
                        }
                        bytes.write(buffer, 0, n);
                        remainingEntry -= n;
                        consumed += n;
                    }

                    String haystack = bytes.toString(StandardCharsets.ISO_8859_1);
                    acc.inspect(haystack);
                }
            }

            return new JarScanResult(plugin.getName(), acc.score, sha.substring(0, Math.min(12, sha.length())), List.copyOf(acc.signals));
        } catch (Exception error) {
            return new JarScanResult(plugin.getName(), 0, "scan-error", List.of("scan-error"));
        }
    }

    private String sha256(Path file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[16_384];
            int n;
            while ((n = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, n);
            }
        }

        StringBuilder out = new StringBuilder();
        for (byte b : digest.digest()) {
            out.append(String.format(Locale.ROOT, "%02x", b));
        }
        return out.toString();
    }

    private void saveReportAsync(String reason, Snapshot s, Map<String, Integer> taskOwners) {
        String dir = getConfig().getString("diagnostics.reports-directory", "reports");
        Path folder = getDataFolder().toPath().resolve(dir == null || dir.isBlank() ? "reports" : dir);

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                Files.createDirectories(folder);
                Path out = folder.resolve(REPORT_TS.format(Instant.now()) + "-" + reason + ".txt");
                try (BufferedWriter writer = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
                    writer.write("ServerGuardian report\n");
                    writer.write("reason=" + reason + "\n");
                    writer.write(String.format(Locale.ROOT,
                            "tps=%.3f mspt=%.3f memory=%.2f%% players=%d entities=%d chunks=%d pendingTasks=%d%n",
                            s.tps1m(), s.mspt(), s.memoryPercent(), s.players(), s.entities(), s.chunks(), s.pendingTasks()));
                    writer.write("taskOwners:\n");
                    taskOwners.entrySet().stream()
                            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                            .forEach(entry -> {
                                try {
                                    writer.write("  " + entry.getKey() + "=" + entry.getValue() + "\n");
                                } catch (IOException ignored) {
                                }
                            });
                }
            } catch (IOException error) {
                getLogger().warning("Could not save diagnostic report: " + error.getMessage());
            }
        });
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("guardian")) {
            return false;
        }
        if (!sender.hasPermission("serverguardian.admin")) {
            tell(sender, prefix() + getConfig().getString("messages.no-permission", "&cНет прав."));
            return true;
        }

        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> showStatus(sender);
            case "report" -> showReport(sender);
            case "plugins" -> showPlugins(sender);
            case "cleanup" -> {
                int removed = safeItemCleanup(true);
                tell(sender, prefix() + "&aSafe cleanup removed &f" + removed + " &aold plain item entities.");
            }
            case "scan" -> runSecurityScan(sender);
            case "reload" -> {
                reloadConfig();
                startMonitor();
                tell(sender, prefix() + getConfig().getString("messages.reloaded", "&aConfig перезагружен."));
            }
            case "mode" -> setMode(sender, args);
            default -> tell(sender, prefix() + "&e/guardian status|report|plugins|cleanup|scan|reload|mode <auto|normal|pressure|emergency>");
        }
        return true;
    }

    private void setMode(CommandSender sender, String[] args) {
        if (args.length < 2) {
            tell(sender, prefix() + "&fMode: &b" + overrideMode + " &7(auto state=" + automaticState + ")");
            return;
        }

        try {
            overrideMode = OverrideMode.valueOf(args[1].toUpperCase(Locale.ROOT));
            tell(sender, prefix() + "&aMode override: &f" + overrideMode);
        } catch (IllegalArgumentException error) {
            tell(sender, prefix() + "&cUnknown mode. Use auto|normal|pressure|emergency");
        }
    }

    @Override
    public LoadState state() {
        return switch (overrideMode) {
            case AUTO -> automaticState;
            case NORMAL -> LoadState.NORMAL;
            case PRESSURE -> LoadState.PRESSURE;
            case EMERGENCY -> LoadState.EMERGENCY;
        };
    }

    @Override
    public double tps1m() {
        return snapshot.get().tps1m();
    }

    @Override
    public double mspt() {
        return snapshot.get().mspt();
    }

    @Override
    public double memoryPercent() {
        return snapshot.get().memoryPercent();
    }

    @Override
    public double cosmeticMultiplier() {
        return switch (state()) {
            case NORMAL -> d("states.cosmetic-multiplier.normal", 1.0, 0.0, 1.0);
            case PRESSURE -> d("states.cosmetic-multiplier.pressure", 0.55, 0.0, 1.0);
            case EMERGENCY -> d("states.cosmetic-multiplier.emergency", 0.25, 0.0, 1.0);
        };
    }

    private String prefix() {
        return getConfig().getString("messages.prefix", "&8[&bGuardian&8] ");
    }

    private String stateColor(LoadState state) {
        return switch (state) {
            case NORMAL -> "&a";
            case PRESSURE -> "&e";
            case EMERGENCY -> "&c";
        };
    }

    private void tell(CommandSender sender, String legacy) {
        Component component = LEGACY.deserialize(legacy == null ? "" : legacy);
        sender.sendMessage(component);
    }

    private int i(String path, int fallback, int min, int max) {
        return Math.max(min, Math.min(max, getConfig().getInt(path, fallback)));
    }

    private double d(String path, double fallback, double min, double max) {
        double value = getConfig().getDouble(path, fallback);
        if (!Double.isFinite(value)) {
            value = fallback;
        }
        return Math.max(min, Math.min(max, value));
    }

    private String clean(double value) {
        return String.format(Locale.ROOT, "%.2f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private enum OverrideMode {
        AUTO,
        NORMAL,
        PRESSURE,
        EMERGENCY
    }

    private record Snapshot(
            double tps1m,
            double mspt,
            double memoryPercent,
            int players,
            int entities,
            int chunks,
            int pendingTasks,
            long timestamp
    ) {}

    private record JarScanResult(
            String pluginName,
            int score,
            String shaPrefix,
            List<String> signals
    ) {}

    private static final class SignalAccumulator {
        int score = 0;
        final List<String> signals = new ArrayList<>();

        void inspect(String text) {
            boolean runtimeExec = text.contains("java/lang/Runtime") && text.contains("exec");
            boolean processBuilder = text.contains("java/lang/ProcessBuilder");
            boolean socket = text.contains("java/net/Socket") || text.contains("java/net/ServerSocket");
            boolean classLoader = text.contains("java/net/URLClassLoader");
            boolean webhook = text.contains("discord.com/api/webhooks")
                    || text.contains("discordapp.com/api/webhooks")
                    || text.contains("api.telegram.org");
            boolean paste = text.contains("pastebin.com") || text.contains("ngrok");
            boolean homeFiles = (text.contains("user.home") || text.contains(".ssh"))
                    && text.contains("FileInputStream");

            add(runtimeExec, 4, "Runtime.exec");
            add(processBuilder, 4, "ProcessBuilder");
            add(socket, 2, "raw-socket");
            add(classLoader, 3, "URLClassLoader");
            add(webhook, 2, "webhook-endpoint");
            add(paste, 2, "remote-paste/tunnel");
            add(homeFiles, 3, "home/ssh-file-access");
        }

        void add(boolean condition, int value, String name) {
            if (!condition || signals.contains(name)) {
                return;
            }
            score += value;
            signals.add(name);
        }
    }
}
