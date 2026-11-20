package com.agentcore.server.common.kb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentcore.server.common.error.ApiException;
import com.agentcore.server.common.error.ErrorCode;
import org.junit.jupiter.api.Test;

class KbFileTypesTest {

    @Test
    void mapsSupportedUploadExtensionsToCanonicalMimeTypes() {
        assertThat(KbFileTypes.requireMime("a.PDF")).isEqualTo("application/pdf");
        assertThat(KbFileTypes.requireMime("a.md")).isEqualTo("text/markdown");
        assertThat(KbFileTypes.requireMime("a.markdown")).isEqualTo("text/markdown");
        assertThat(KbFileTypes.requireMime("a.txt")).isEqualTo("text/plain");
        assertThat(KbFileTypes.requireMime("a.docx"))
                .isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    }

    @Test
    void rejectsUnsupportedFormats() {
        assertThatThrownBy(() -> KbFileTypes.requireMime("a.exe"))
                .isInstanceOf(ApiException.class)
                .extracting((error) -> ((ApiException) error).code())
                .isEqualTo(ErrorCode.KB_UNSUPPORTED_FORMAT);
        assertThatThrownBy(() -> KbFileTypes.requireMime(null))
                .isInstanceOf(ApiException.class);
    }
}
