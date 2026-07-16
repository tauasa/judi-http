package org.tauasa.apps.http;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;

/**
 * Per-connection state attached to each SelectionKey.
 *
 * Life-cycle: READING → WRITING → (keep-alive: back to READING) or close.
 * All I/O on the connection is non-blocking; partial reads and writes are
 * resumed on subsequent selector wake-ups.
 */
final class HttpConnection {

    enum State { READING, WRITING }

    private static final byte[] CRLF_CRLF = {'\r', '\n', '\r', '\n'};

    final SocketChannel channel;
    final String remoteAddress;

    State state = State.READING;
    long lastActivityNanos = System.nanoTime();
    boolean closeAfterWrite = false;

    /** Accumulates request header bytes until CRLFCRLF is seen. */
    private ByteBuffer requestBuffer;
    private final int maxRequestBytes;

    /** Pending response: header bytes, then optionally a file body. */
    ByteBuffer responseHeader;
    FileChannel bodyFile;
    long bodyPosition;
    long bodyRemaining;

    HttpConnection(SocketChannel channel, int maxRequestBytes) {
        this.channel = channel;
        this.maxRequestBytes = maxRequestBytes;
        this.requestBuffer = ByteBuffer.allocate(Math.min(4096, maxRequestBytes));
        String addr;
        try {
            addr = String.valueOf(channel.getRemoteAddress());
        } catch (IOException e) {
            addr = "unknown";
        }
        this.remoteAddress = addr;
    }

    void touch() {
        lastActivityNanos = System.nanoTime();
    }

    /**
     * Reads available bytes from the channel into the request buffer.
     *
     * @return the complete header block (without the trailing CRLFCRLF) once a
     *         full request head has arrived, or null if more data is needed
     * @throws java.io.EOFException                     if the peer closed the connection
     * @throws HttpRequest.HttpProtocolException        if the head exceeds maxRequestBytes
     */
    byte[] readRequestHead() throws IOException, HttpRequest.HttpProtocolException {
        int n = channel.read(requestBuffer);
        if (n == -1) {
            throw new java.io.EOFException("peer closed connection");
        }
        if (n == 0) {
            return null;
        }
        touch();

        int end = findHeaderEnd(requestBuffer);
        if (end >= 0) {
            byte[] head = new byte[end];
            ByteBuffer dup = requestBuffer.duplicate().flip();
            dup.get(head);
            // Any pipelined bytes beyond the head are intentionally dropped;
            // this server handles one request at a time per connection.
            requestBuffer.clear();
            return head;
        }

        if (!requestBuffer.hasRemaining()) {
            if (requestBuffer.capacity() >= maxRequestBytes) {
                throw new HttpRequest.HttpProtocolException(431, "Request header too large");
            }
            ByteBuffer bigger = ByteBuffer.allocate(
                    Math.min(requestBuffer.capacity() * 2, maxRequestBytes));
            requestBuffer.flip();
            bigger.put(requestBuffer);
            requestBuffer = bigger;
        }
        return null;
    }

    /** Scans the buffer's written region for CRLFCRLF; returns its start index or -1. */
    private static int findHeaderEnd(ByteBuffer buf) {
        int limit = buf.position();
        outer:
        for (int i = 0; i <= limit - 4; i++) {
            for (int j = 0; j < 4; j++) {
                if (buf.get(i + j) != CRLF_CRLF[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    /** Arms a response for writing. bodyFile may be null (header-only / error page). */
    void setResponse(ByteBuffer header, FileChannel file, long fileLength, boolean closeAfter) {
        this.responseHeader = header;
        this.bodyFile = file;
        this.bodyPosition = 0;
        this.bodyRemaining = (file != null) ? fileLength : 0;
        this.closeAfterWrite = closeAfter;
        this.state = State.WRITING;
    }

    /**
     * Writes as much of the pending response as the socket will take.
     *
     * @return true when the entire response has been flushed
     */
    boolean writeResponse() throws IOException {
        touch();

        if (responseHeader != null && responseHeader.hasRemaining()) {
            channel.write(responseHeader);
            if (responseHeader.hasRemaining()) {
                return false; // socket buffer full; wait for next OP_WRITE
            }
        }

        while (bodyFile != null && bodyRemaining > 0) {
            long sent = bodyFile.transferTo(bodyPosition, bodyRemaining, channel);
            if (sent <= 0) {
                return false; // would block
            }
            bodyPosition += sent;
            bodyRemaining -= sent;
        }
        return true;
    }

    /** Resets state for the next request on a kept-alive connection. */
    void resetForNextRequest() {
        closeBodyFile();
        responseHeader = null;
        requestBuffer.clear();
        state = State.READING;
        touch();
    }

    void closeBodyFile() {
        if (bodyFile != null) {
            try {
                bodyFile.close();
            } catch (IOException ignored) {
                // best-effort close
            }
            bodyFile = null;
        }
    }
}
