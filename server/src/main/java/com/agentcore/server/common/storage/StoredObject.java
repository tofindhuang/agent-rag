package com.agentcore.server.common.storage;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;

/**
 * An object body plus the metadata stored alongside it. The caller owns the
 * stream and must close this record (try-with-resources).
 */
public record StoredObject(
        InputStream body, String contentType, String sha256, long size) implements Closeable {

    @Override
    public void close() throws IOException {
        body.close();
    }
}
