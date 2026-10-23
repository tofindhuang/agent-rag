package com.agentcore.server.common.kb;

/** Collection vector size no longer matches the embedding dimension. */
public final class KbDimensionMismatchException extends KbStoreException {

    public KbDimensionMismatchException(String message) {
        super(message, null, false, false);
    }
}
