package com.agentcore.server.common.kb;

/** Model-list lookup failed upstream; the settings UI falls back to manual input. */
public class KbModelsException extends RuntimeException {

    public KbModelsException(String message, Throwable cause) {
        super(message, cause);
    }
}
