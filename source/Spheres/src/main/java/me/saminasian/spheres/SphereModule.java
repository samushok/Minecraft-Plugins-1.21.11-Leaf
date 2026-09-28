package me.saminasian.spheres;
import java.io.File;
import java.util.logging.Logger;
import org.bukkit.configuration.file.FileConfiguration;
/** Both ability modules share the single registered plugin and its configuration. */
abstract class SphereModule {
    protected final SpheresPlugin host;
    SphereModule(SpheresPlugin host) { this.host = host; }
    public final SpheresPlugin getHost() { return host; }
    public final FileConfiguration getConfig() { return host.getConfig(); }
    public final File getDataFolder() { return host.getDataFolder(); }
    public final Logger getLogger() { return host.getLogger(); }
    public abstract void start();
    public abstract void stop();
}
