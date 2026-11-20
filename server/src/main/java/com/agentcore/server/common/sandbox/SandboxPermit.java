package com.agentcore.server.common.sandbox;

/** Admission permit held for one execution; always released in the run's finally block. */
public interface SandboxPermit {

    void release();

    static SandboxPermit noop() {
        return () -> {
        };
    }
}
