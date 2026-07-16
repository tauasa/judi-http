package org.tauasa.apps.http;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A minimal, parsed HTTP/1.x request (request line + headers).
 * Bodies are not read; this server serves static content via GET/HEAD only.
 */
public record HttpRequest(
        String method,
        String rawPath,
        String path,
        String query,
        String version,
        Map<String, String> headers) {

    /** Returns a header value by case-insensitive name, or null. */
    public String header(String name) {
        return headers.get(name.toLowerCase(Locale.ROOT));
    }

    public boolean wantsKeepAlive() {
        String conn = header("connection");
        if ("HTTP/1.1".equals(version)) {
            return conn == null || !conn.equalsIgnoreCase("close");
        }
        // HTTP/1.0: only keep alive if explicitly requested
        return conn != null && conn.equalsIgnoreCase("keep-alive");
    }

    /**
     * Parses the given header block (everything up to, but not including, the
     * terminating CRLFCRLF).
     *
     * @throws HttpProtocolException if the request is malformed
     */
    public static HttpRequest parse(byte[] headerBytes) throws HttpProtocolException {
        String text = new String(headerBytes, StandardCharsets.US_ASCII);
        String[] lines = text.split("\r\n");
        if (lines.length == 0 || lines[0].isBlank()) {
            throw new HttpProtocolException(400, "Empty request");
        }

        String[] requestLine = lines[0].split(" ");
        if (requestLine.length != 3) {
            throw new HttpProtocolException(400, "Malformed request line");
        }
        String method = requestLine[0];
        String target = requestLine[1];
        String version = requestLine[2];

        if (!version.equals("HTTP/1.1") && !version.equals("HTTP/1.0")) {
            throw new HttpProtocolException(505, "HTTP version not supported");
        }

        String path = target;
        String query = null;
        int q = target.indexOf('?');
        if (q >= 0) {
            path = target.substring(0, q);
            query = target.substring(q + 1);
        }
        path = urlDecode(path);

        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) break;
            int colon = line.indexOf(':');
            if (colon <= 0) {
                throw new HttpProtocolException(400, "Malformed header: " + line);
            }
            String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            headers.put(name, value);
        }

        return new HttpRequest(method, target, path, query, version, headers);
    }

    /** Percent-decodes a URL path (does not treat '+' as space). */
    private static String urlDecode(String s) throws HttpProtocolException {
        if (s.indexOf('%') < 0) return s;
        var out = new java.io.ByteArrayOutputStream(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%') {
                if (i + 2 >= s.length()) {
                    throw new HttpProtocolException(400, "Bad percent-encoding");
                }
                try {
                    out.write(Integer.parseInt(s.substring(i + 1, i + 3), 16));
                } catch (NumberFormatException e) {
                    throw new HttpProtocolException(400, "Bad percent-encoding");
                }
                i += 2;
            } else {
                out.write(c);
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    /** Thrown for malformed / unsupported requests; carries an HTTP status. */
    public static class HttpProtocolException extends Exception {
        private final int status;

        public HttpProtocolException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }
}
