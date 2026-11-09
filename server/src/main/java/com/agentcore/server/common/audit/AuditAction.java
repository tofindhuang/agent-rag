package com.agentcore.server.common.audit;

/**
 * The fixed audit vocabulary (ADR-0033). Wire names are stable strings so the
 * UI can map them to labels and operators can grep them in logs.
 */
public enum AuditAction {
    AUTH_REGISTER("auth.register"),
    AUTH_LOGIN("auth.login"),
    AUTH_LOGIN_FAILED("auth.login.failed"),
    AUTH_REFRESH_REUSE("auth.refresh.reuse"),
    AUTH_LOGOUT_ALL("auth.logout.all"),
    AUTH_PASSWORD_SET("auth.password.set"),
    AUTH_PASSWORD_CHANGE("auth.password.change"),
    AUTH_IDENTITY_LINK("auth.identity.link"),
    AUTH_IDENTITY_UNLINK("auth.identity.unlink"),

    MODEL_PROVIDER_CREATE("model.provider.create"),
    MODEL_PROVIDER_UPDATE("model.provider.update"),
    MODEL_PROVIDER_DELETE("model.provider.delete"),
    MODEL_PROVIDER_DEFAULT("model.provider.default"),

    KB_SETTINGS_UPDATE("kb.settings.update"),
    KB_SETTINGS_RESET("kb.settings.reset"),
    KB_THRESHOLD_UPDATE("kb.threshold.update"),

    SESSION_PURGE("session.purge"),
    SESSION_ARCHIVE("session.archive"),
    SESSION_RESTORE("session.restore"),
    SESSION_BRANCH("session.branch"),
    KB_DOCUMENT_DELETE("kb.document.delete"),
    KB_DELETE("kb.delete"),
    MEMORY_FACT_DELETE("memory.fact.delete"),
    USER_DATA_EXPORT("user.data.export");

    private final String wire;

    AuditAction(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }
}
