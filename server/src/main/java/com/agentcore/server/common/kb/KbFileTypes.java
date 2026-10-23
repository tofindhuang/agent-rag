package com.agentcore.server.common.kb;

import com.agentcore.server.common.error.ApiException;
import com.agentcore.server.common.error.ErrorCode;
import com.agentcore.server.common.storage.ObjectKey;
import java.util.Map;

/** KB format allow-list and the fixed upload/URL limits from the spec. */
public final class KbFileTypes {

    public static final long MAX_UPLOAD_BYTES = 20L * 1024 * 1024;
    public static final long MAX_URL_BYTES = 5L * 1024 * 1024;
    public static final int MAX_URL_LENGTH = 2048;
    public static final String HTML_MIME = "text/html";

    private static final Map<String, String> MIME_BY_EXTENSION = Map.of(
            "pdf", "application/pdf",
            "md", "text/markdown",
            "markdown", "text/markdown",
            "txt", "text/plain",
            "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

    private KbFileTypes() {}

    /** Canonical MIME for an upload; unsupported extensions are a 400. */
    public static String requireMime(String filename) {
        String mime = MIME_BY_EXTENSION.get(ObjectKey.extension(filename));
        if (mime == null) {
            throw new ApiException(ErrorCode.KB_UNSUPPORTED_FORMAT,
                    "supported upload formats: PDF, Markdown, TXT, DOCX");
        }
        return mime;
    }
}
