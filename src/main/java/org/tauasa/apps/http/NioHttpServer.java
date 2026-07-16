package org.tauasa.apps.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZonedDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A single-threaded, non-blocking (java.nio) HTTP/1.1 static file server.
 *
 * One Selector drives all connections: accepts, reads, and writes are all
 * event-driven and never block. File bodies are streamed to sockets with
 * zero-copy FileChannel.transferTo, resuming on OP_WRITE when the socket
 * buffer fills.
 */
public final class NioHttpServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(NioHttpServer.class);
    private static final Logger accessLog = LoggerFactory.getLogger("access");

    private static final DateTimeFormatter HTTP_DATE =
            DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC);

    private final ServerConfig config;
    private final Selector selector;
    private final ServerSocketChannel serverChannel;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public NioHttpServer(ServerConfig config) throws IOException {
        this.config = config;
        this.selector = Selector.open();
        this.serverChannel = ServerSocketChannel.open();
        this.serverChannel.configureBlocking(false);
        this.serverChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
        this.serverChannel.bind(new InetSocketAddress(config.host(), config.port()), 512);
        this.serverChannel.register(selector, SelectionKey.OP_ACCEPT);
    }

    /** Runs the event loop on the calling thread until {@link #close()} is invoked. */
    public void run() {
        running.set(true);
        log.info("judi-http listening on {}:{} serving {}",
                config.host(), config.port(), config.documentRoot());

        long idleTimeoutNanos = TimeUnit.SECONDS.toNanos(config.idleTimeoutSeconds());
        long lastSweep = System.nanoTime();

        while (running.get()) {
            try {
                selector.select(1000);
            } catch (IOException e) {
                log.error("Selector failure", e);
                break;
            }

            Iterator<SelectionKey> it = selector.selectedKeys().iterator();
            while (it.hasNext()) {
                SelectionKey key = it.next();
                it.remove();
                if (!key.isValid()) continue;
                try {
                    if (key.isAcceptable()) {
                        accept();
                    } else {
                        if (key.isReadable()) handleRead(key);
                        if (key.isValid() && key.isWritable()) handleWrite(key);
                    }
                } catch (CancelledKeyException ignored) {
                    // connection torn down mid-dispatch
                } catch (Exception e) {
                    log.debug("Connection error: {}", e.toString());
                    closeConnection(key);
                }
            }

            long now = System.nanoTime();
            if (now - lastSweep > TimeUnit.SECONDS.toNanos(5)) {
                sweepIdle(idleTimeoutNanos, now);
                lastSweep = now;
            }
        }
    }

    private static final class CancelledKeyException extends RuntimeException {
    }

    // ---- Accept --------------------------------------------------------

    private void accept() throws IOException {
        SocketChannel client;
        while ((client = serverChannel.accept()) != null) {
            client.configureBlocking(false);
            client.setOption(StandardSocketOptions.TCP_NODELAY, true);
            HttpConnection conn = new HttpConnection(client, config.maxRequestBytes());
            client.register(selector, SelectionKey.OP_READ, conn);
            log.debug("Accepted {}", conn.remoteAddress);
        }
    }

    // ---- Read / parse / dispatch ----------------------------------------

    private void handleRead(SelectionKey key) throws IOException {
        HttpConnection conn = (HttpConnection) key.attachment();
        if (conn.state != HttpConnection.State.READING) return;

        byte[] head;
        try {
            head = conn.readRequestHead();
        } catch (EOFException e) {
            closeConnection(key);
            return;
        } catch (HttpRequest.HttpProtocolException e) {
            respondError(key, conn, e.status(), e.getMessage(), null);
            return;
        }
        if (head == null) return; // need more bytes

        HttpRequest request;
        try {
            request = HttpRequest.parse(head);
        } catch (HttpRequest.HttpProtocolException e) {
            respondError(key, conn, e.status(), e.getMessage(), null);
            return;
        }

        dispatch(key, conn, request);
    }

    private void dispatch(SelectionKey key, HttpConnection conn, HttpRequest request) throws IOException {
        boolean head = "HEAD".equals(request.method());
        if (!head && !"GET".equals(request.method())) {
            respondError(key, conn, 405, "Method Not Allowed", request);
            return;
        }

        Path file = resolve(request.path());
        if (file == null) {
            respondError(key, conn, 403, "Forbidden", request);
            return;
        }

        if (Files.isDirectory(file)) {
            Path index = findIndex(file);
            if (index == null) {
                respondError(key, conn, 404, "Not Found", request);
                return;
            }
            file = index;
        }

        if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
            respondError(key, conn, 404, "Not Found", request);
            return;
        }

        FileChannel fc;
        long length;
        try {
            fc = FileChannel.open(file, StandardOpenOption.READ);
            length = fc.size();
        } catch (IOException e) {
            log.debug("Failed to open {}: {}", file, e.toString());
            respondError(key, conn, 500, "Internal Server Error", request);
            return;
        }
        boolean keepAlive = config.keepAlive() && request.wantsKeepAlive();

        ByteBuffer header = buildHeader(200, "OK",
                MimeTypes.forFileName(file.getFileName().toString()), length, keepAlive);

        if (head) {
            fc.close();
            conn.setResponse(header, null, 0, !keepAlive);
        } else {
            conn.setResponse(header, fc, length, !keepAlive);
        }

        accessLog.info("{} \"{} {} {}\" 200 {}",
                conn.remoteAddress, request.method(), request.rawPath(), request.version(), length);

        enableWrite(key);
        handleWrite(key); // opportunistic first flush
    }

    /**
     * Resolves a URL path against the document root, rejecting anything that
     * escapes it (path traversal) or contains NUL bytes.
     */
    private Path resolve(String urlPath) {
        if (urlPath.indexOf('\0') >= 0) return null;
        String rel = urlPath.startsWith("/") ? urlPath.substring(1) : urlPath;
        Path resolved = config.documentRoot().resolve(rel).normalize();
        if (!resolved.startsWith(config.documentRoot())) {
            return null;
        }
        return resolved;
    }

    private Path findIndex(Path dir) {
        for (String name : config.indexFiles()) {
            Path candidate = dir.resolve(name);
            if (Files.isRegularFile(candidate) && Files.isReadable(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    // ---- Write ----------------------------------------------------------

    private void handleWrite(SelectionKey key) throws IOException {
        HttpConnection conn = (HttpConnection) key.attachment();
        if (conn.state != HttpConnection.State.WRITING) return;

        boolean done = conn.writeResponse();
        if (!done) {
            enableWrite(key);
            return;
        }

        if (conn.closeAfterWrite) {
            closeConnection(key);
        } else {
            conn.resetForNextRequest();
            key.interestOps(SelectionKey.OP_READ);
        }
    }

    private void enableWrite(SelectionKey key) {
        if (!key.isValid()) throw new CancelledKeyException();
        key.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
    }

    // ---- Responses --------------------------------------------------------

    private void respondError(SelectionKey key, HttpConnection conn, int status,
                              String reason, HttpRequest request) throws IOException {
        String customPage = config.errorPages().get(status);
        byte[] customBody = customPage != null ? readErrorPage(customPage) : null;

        byte[] body;
        String contentType;
        if (customBody != null) {
            body = customBody;
            contentType = MimeTypes.forFileName(customPage);
        } else {
            body = ("<html><head><title>" + status + " " + reason + "</title></head>"
                    + "<body><h1>" + status + " " + reason + "</h1><hr>"
                    + "<p>" + config.serverName() + "</p></body></html>")
                    .getBytes(StandardCharsets.UTF_8);
            contentType = "text/html; charset=utf-8";
        }

        boolean keepAlive = status < 500 && status != 400 && status != 431
                && config.keepAlive() && request != null && request.wantsKeepAlive();

        ByteBuffer header = buildHeader(status, reason, contentType, body.length, keepAlive);
        ByteBuffer full = ByteBuffer.allocate(header.remaining() + body.length);
        full.put(header).put(body).flip();

        conn.setResponse(full, null, 0, !keepAlive);

        String line = request != null
                ? request.method() + " " + request.rawPath() + " " + request.version()
                : "-";
        accessLog.info("{} \"{}\" {} {}", conn.remoteAddress, line, status, body.length);

        enableWrite(key);
        handleWrite(key);
    }

    /**
     * Loads a custom error page from {@code urlPath}, resolved against the
     * document root the same way normal requests are. Returns null (falling
     * back to the built-in page) if unset, missing, or unreadable.
     */
    private byte[] readErrorPage(String urlPath) {
        Path file = resolve(urlPath);
        if (file == null || !Files.isRegularFile(file) || !Files.isReadable(file)) {
            return null;
        }
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            log.debug("Failed to read custom error page {}: {}", file, e.toString());
            return null;
        }
    }

    private ByteBuffer buildHeader(int status, String reason, String contentType,
                                   long contentLength, boolean keepAlive) {
        String header = "HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Server: " + config.serverName() + "\r\n"
                + "Date: " + HTTP_DATE.format(ZonedDateTime.now(ZoneOffset.UTC)) + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + contentLength + "\r\n"
                + "Connection: " + (keepAlive ? "keep-alive" : "close") + "\r\n"
                + "\r\n";
        return ByteBuffer.wrap(header.getBytes(StandardCharsets.US_ASCII));
    }

    // ---- Housekeeping ---------------------------------------------------

    private void sweepIdle(long idleTimeoutNanos, long now) {
        for (SelectionKey key : selector.keys()) {
            Object att = key.attachment();
            if (att instanceof HttpConnection conn
                    && now - conn.lastActivityNanos > idleTimeoutNanos) {
                log.debug("Closing idle connection {}", conn.remoteAddress);
                closeConnection(key);
            }
        }
    }

    private void closeConnection(SelectionKey key) {
        Object att = key.attachment();
        if (att instanceof HttpConnection conn) {
            conn.closeBodyFile();
        }
        key.cancel();
        try {
            key.channel().close();
        } catch (IOException ignored) {
            // best-effort close
        }
    }

    @Override
    public void close() throws IOException {
        if (running.compareAndSet(true, false)) {
            selector.wakeup();
        }
        for (SelectionKey key : selector.keys()) {
            try {
                key.channel().close();
            } catch (IOException ignored) {
                // best-effort close
            }
        }
        selector.close();
        log.info("judi-http stopped");
    }
}
