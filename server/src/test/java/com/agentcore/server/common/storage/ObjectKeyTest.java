package com.agentcore.server.common.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ObjectKeyTest {

    private static final String SHA =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void buildsUserDocumentDigestKey() {
        String documentId = UUID.randomUUID().toString();

        String key = ObjectKey.of(42, documentId, SHA, "Annual Report.PDF");

        assertThat(key).isEqualTo("42/" + documentId + "/" + SHA + ".pdf");
    }

    @Test
    void keepsOnlySafeLowercaseExtensions() {
        assertThat(ObjectKey.extension("notes.MD")).isEqualTo("md");
        assertThat(ObjectKey.extension("archive.tar.gz")).isEqualTo("gz");
        assertThat(ObjectKey.extension("no-extension")).isEqualTo("bin");
        assertThat(ObjectKey.extension(null)).isEqualTo("bin");
        assertThat(ObjectKey.extension("weird!ext")).isEqualTo("bin");
        assertThat(ObjectKey.extension("toolongextension123")).isEqualTo("bin");
    }

    @Test
    void rejectsInvalidDocumentIdAndDigest() {
        assertThatThrownBy(() -> ObjectKey.of(1, " ", SHA, "a.pdf"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ObjectKey.of(1, UUID.randomUUID().toString(), "not-a-sha", "a.pdf"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ObjectKey.of(
                        1, UUID.randomUUID().toString(), SHA.toUpperCase(), "a.pdf"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
