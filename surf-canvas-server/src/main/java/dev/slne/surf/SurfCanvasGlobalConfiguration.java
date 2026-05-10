package dev.slne.surf;

import io.canvasmc.canvas.configuration.*;
import org.slf4j.*;

import java.nio.file.*;

public class SurfCanvasGlobalConfiguration extends Part {
    private static final Path CONFIG_PATH = Path.of("config", "surf-canvas-server.yml").toAbsolutePath().normalize();

    protected static final int CHAR_LIM = 90;

    public static final Logger LOGGER = LoggerFactory.getLogger("SurfCanvas");

    public static final int INFO = 0;
    public static final int WARN = 1;
    public static final int ERROR = 2;

    private static SurfCanvasGlobalConfiguration instance;

    static {
        reload();
    }

    public static void reload() {
        LOGGER.info("Loading SurfCanvas server configuration...");
        ConfigurationProvider.buildSolidConfiguration(
            CONFIG_PATH,
            SurfCanvasGlobalConfiguration::new,
            CHAR_LIM,
            new io.canvasmc.canvas.configuration.Resolver<SurfCanvasGlobalConfiguration>() {
                @Override
                public void onDiffAdd(String fullyQualifiedName) {
                    LOGGER.info("Added new server-wide configuration option: \"{}\"", fullyQualifiedName);
                }

                @Override
                public void onDiffRemove(String fullyQualifiedName) {
                    LOGGER.info("Server-wide configuration option \"{}\" no longer exists and is now removed.", fullyQualifiedName);
                }

                @Override
                public void onFinishLoad(dev.slne.surf.SurfCanvasGlobalConfiguration instance) {
                    postLoad(instance);
                }
            },
            Style.create()
                .literal("Global configuration for SurfCanvas").endLine()
                .blank()
                .wordWrap(
                    "This is the server-wide configuration file provided by SurfCanvas. This config holds options",
                    "that are set across the entire server, and cannot be overridden per-world. You are free to modify,",
                    "add, or remove comments as you please."
                ).endLine()
                .blank()
                .wordWrap(
                    "You may refresh this configuration at runtime using the \"/surfcanvas reload\" command, however",
                    "it is not recommended to do this during production, as this can cause issues like unexpected crashes",
                    "or unintended behavior."
                ).endLine()
                .blank()
                .wordWrap(
                    "If you have questions about certain configuration options please think for yourself"
                ).endLine()
                .compile(60)
        );
    }

    private static void postLoad(SurfCanvasGlobalConfiguration instance) {
        SurfCanvasGlobalConfiguration.instance = instance;
        Validator.validateObject(instance);
    }

    public static SurfCanvasGlobalConfiguration getInstance() {
        return instance;
    }


    public PluginConfiguration plugin = new PluginConfiguration();
    public static class PluginConfiguration extends Part {
        {
            option("shutdown").docs("Shutdown related configuration for plugin shutdown process");
        }

        public ShutdownConfiguration shutdown = new ShutdownConfiguration();

        public static class ShutdownConfiguration extends Part {
            {
                option("timeout")
                    .docs("The maximum amount of time (in seconds) to wait for a single plugin to shutdown before giving up.")
                    .greaterThanOrEqualTo(1);
            }

            public int timeout = 60;
        }
    }
}
