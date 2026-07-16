package org.tauasa.apps.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;

/**
 * judi-http entry point.
 *
 * Config file: /etc/judi-http/config/server.yml (override: -Djudi.config=...)
 * Logs:        /var/log/judi-http               (override: -Djudi.log.dir=...)
 * Doc root:    /etc/judi-http/www               (set in server.yml)
 */
public final class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        Path configPath = Path.of(
                System.getProperty("judi.config",
                        ServerConfig.DEFAULT_CONFIG_PATH.toString()));

        ServerConfig config;
        try {
            config = ServerConfig.load(configPath);
        } catch (IOException e) {
            log.error("Failed to read config {}: {}", configPath, e.getMessage());
            System.exit(1);
            return;
        }
        log.info("Loaded configuration from {}", configPath);

        try {
            NioHttpServer server = new NioHttpServer(config);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    server.close();
                } catch (IOException e) {
                    log.warn("Error during shutdown: {}", e.getMessage());
                }
            }, "judi-shutdown"));
            server.run();
        } catch (IOException e) {
            log.error("Failed to start server on {}:{} - {}",
                    config.host(), config.port(), e.getMessage());
            System.exit(1);
        }
    }

    private Main() {
    }
}
