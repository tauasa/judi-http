package org.tauasa.apps.http;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable server configuration, loaded from a single YAML file.
 *
 * Default location: /etc/judi-http/config/server.yml
 * Override with -Djudi.config=/path/to/server.yml
 */
public record ServerConfig(
        String host,
        int port,
        Path documentRoot,
        List<String> indexFiles,
        int maxRequestBytes,
        int idleTimeoutSeconds,
        boolean keepAlive,
        String serverName,
        Map<Integer, String> errorPages) {

    public static final Path DEFAULT_CONFIG_PATH = Path.of("/etc/judi-http/config/server.yml");

    public static ServerConfig defaults() {
        return new ServerConfig(
                "0.0.0.0",
                8080,
                Path.of("/etc/judi-http/www"),
                List.of("index.html", "index.htm"),
                16 * 1024,
                30,
                true,
                "judi-http/1.0",
                Map.of());
    }

    /**
     * Loads configuration from the given YAML file. Any key not present falls
     * back to the built-in default. If the file does not exist, pure defaults
     * are returned.
     */
    public static ServerConfig load(Path configPath) throws IOException {
        ServerConfig d = defaults();
        if (configPath == null || !Files.isRegularFile(configPath)) {
            return d;
        }

        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        Map<String, Object> root;
        try (InputStream in = Files.newInputStream(configPath)) {
            Object parsed = yaml.load(in);
            root = (parsed instanceof Map<?, ?> m) ? castMap(m) : Collections.emptyMap();
        }

        Map<String, Object> server = subMap(root, "server");
        Map<String, Object> http = subMap(root, "http");

        return new ServerConfig(
                str(server, "host", d.host()),
                intVal(server, "port", d.port()),
                Path.of(str(server, "documentRoot", d.documentRoot().toString())).toAbsolutePath().normalize(),
                strList(http, "indexFiles", d.indexFiles()),
                intVal(http, "maxRequestBytes", d.maxRequestBytes()),
                intVal(http, "idleTimeoutSeconds", d.idleTimeoutSeconds()),
                boolVal(http, "keepAlive", d.keepAlive()),
                str(server, "name", d.serverName()),
                errorPages(http, "errorPages", d.errorPages()));
    }

    // ---- YAML helpers -------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    private static Map<String, Object> subMap(Map<String, Object> parent, String key) {
        Object v = parent.get(key);
        return (v instanceof Map<?, ?> m) ? castMap(m) : Collections.emptyMap();
    }

    private static String str(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        return (v != null) ? String.valueOf(v) : def;
    }

    private static int intVal(Map<String, Object> m, String key, int def) {
        Object v = m.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                // fall through to default
            }
        }
        return def;
    }

    private static boolean boolVal(Map<String, Object> m, String key, boolean def) {
        Object v = m.get(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return Boolean.parseBoolean(s.trim());
        return def;
    }

    private static List<String> strList(Map<String, Object> m, String key, List<String> def) {
        Object v = m.get(key);
        if (v instanceof List<?> list && !list.isEmpty()) {
            return list.stream().map(String::valueOf).toList();
        }
        return def;
    }

    /**
     * Reads a status-code -> document-root-relative-path map, e.g.
     * {@code errorPages: { 400: /40x.html, 500: /50x.html }}. Keys that
     * aren't valid integers are skipped.
     */
    private static Map<Integer, String> errorPages(Map<String, Object> m, String key, Map<Integer, String> def) {
        Object v = m.get(key);
        if (!(v instanceof Map<?, ?> raw) || raw.isEmpty()) {
            return def;
        }
        Map<Integer, String> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : raw.entrySet()) {
            try {
                int status = Integer.parseInt(String.valueOf(e.getKey()).trim());
                result.put(status, String.valueOf(e.getValue()));
            } catch (NumberFormatException ignored) {
                // skip invalid status key
            }
        }
        return result.isEmpty() ? def : Collections.unmodifiableMap(result);
    }
}
