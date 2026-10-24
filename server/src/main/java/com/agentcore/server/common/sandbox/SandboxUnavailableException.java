package com.agentcore.server.common.sandbox;

/** Docker daemon or sandbox image is not usable. */
public class SandboxUnavailableException extends RuntimeException {

    public SandboxUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public SandboxUnavailableException(String message) {
        super(message);
    }
}
