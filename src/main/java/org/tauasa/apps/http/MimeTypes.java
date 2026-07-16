package org.tauasa.apps.http;

import java.util.Locale;
import java.util.Map;

/** File-extension → Content-Type lookup for common static assets. */
final class MimeTypes {

    private static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("html", "text/html; charset=utf-8"),
            Map.entry("htm", "text/html; charset=utf-8"),
            Map.entry("css", "text/css; charset=utf-8"),
            Map.entry("js", "text/javascript; charset=utf-8"),
            Map.entry("mjs", "text/javascript; charset=utf-8"),
            Map.entry("json", "application/json; charset=utf-8"),
            Map.entry("xml", "application/xml; charset=utf-8"),
            Map.entry("txt", "text/plain; charset=utf-8"),
            Map.entry("md", "text/markdown; charset=utf-8"),
            Map.entry("csv", "text/csv; charset=utf-8"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("ico", "image/x-icon"),
            Map.entry("woff", "font/woff"),
            Map.entry("woff2", "font/woff2"),
            Map.entry("ttf", "font/ttf"),
            Map.entry("otf", "font/otf"),
            Map.entry("pdf", "application/pdf"),
            Map.entry("zip", "application/zip"),
            Map.entry("gz", "application/gzip"),
            Map.entry("wasm", "application/wasm"),
            Map.entry("mp3", "audio/mpeg"),
            Map.entry("mp4", "video/mp4"),
            Map.entry("webm", "video/webm"));

    static final String DEFAULT = "application/octet-stream";

    static String forFileName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) return DEFAULT;
        return TYPES.getOrDefault(
                fileName.substring(dot + 1).toLowerCase(Locale.ROOT), DEFAULT);
    }

    private MimeTypes() {
    }
}
