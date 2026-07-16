# judi-http

A simple, fast, standalone HTTP server built on Java NIO non-blocking I/O.

A single `Selector` event loop drives every connection — accepts, reads, and
writes never block. Static file bodies are streamed with zero-copy
`FileChannel.transferTo`, resuming on `OP_WRITE` whenever the socket buffer
fills, so one thread comfortably serves many concurrent connections.

## Requirements

- Java 17+
- Maven 3.6+

Runtime dependencies (shaded into the fat jar): SnakeYAML (config), SLF4J +
Logback (logging). Nothing else.

## Build

```bash
mvn package
```

Produces `target/judi-http.jar` — a self-contained fat jar (Maven Shade).

## Install

```bash
sudo mkdir -p /etc/judi-http/config /etc/judi-http/www /var/log/judi-http
sudo cp config/server.yml /etc/judi-http/config/server.yml
# put your content in /etc/judi-http/www
```

## Run

```bash
java -jar target/judi-http.jar
```

The server reads `/etc/judi-http/config/server.yml`, serves files from
`/etc/judi-http/www`, and listens on port 8080 by default.

Overrides (handy for local development without touching /etc or /var):

```bash
java -Djudi.config=./config/server.yml \
     -Djudi.log.dir=./logs \
     -jar target/judi-http.jar
```

## Configuration

Single YAML file at `/etc/judi-http/config/server.yml`. Every key is optional;
defaults shown:

```yaml
server:
  host: 0.0.0.0
  port: 8080
  documentRoot: /etc/judi-http/www
  name: judi-http/1.0

http:
  indexFiles: [index.html, index.htm]
  maxRequestBytes: 16384
  idleTimeoutSeconds: 30
  keepAlive: true
```

## Logging

Logback writes to `/var/log/judi-http`:

- `server.log` — application/lifecycle log
- `access.log` — one line per request

Both roll daily (and at 100 MB), keep 30 days, capped at 1 GB total. The
running user needs write access to `/var/log/judi-http`.

## Behavior

- HTTP/1.1 and HTTP/1.0, `GET` and `HEAD` (anything else → 405)
- Persistent connections (keep-alive) with idle-connection sweeping
- Directory requests serve the first matching index file
- Path traversal is rejected (403), including percent-encoded attempts
- Correct `Content-Type` for common web asset extensions

## systemd (optional)

```ini
[Unit]
Description=judi-http NIO static file server
After=network.target

[Service]
ExecStart=/usr/bin/java -jar /opt/judi-http/judi-http.jar
Restart=on-failure
User=www-data
Group=www-data

[Install]
WantedBy=multi-user.target
```

## Project layout

```
judi-http/
├── pom.xml
├── config/server.yml                  # sample config → /etc/judi-http/config/
└── src/main/
    ├── java/org/tauasa/apps/http/
    │   ├── Main.java                  # entry point, shutdown hook
    │   ├── ServerConfig.java          # YAML config record + loader
    │   ├── NioHttpServer.java         # selector event loop, routing, responses
    │   ├── HttpConnection.java        # per-connection state machine
    │   ├── HttpRequest.java           # HTTP/1.x request-head parser
    │   └── MimeTypes.java             # extension → Content-Type
    └── resources/logback.xml          # logs to /var/log/judi-http
```
