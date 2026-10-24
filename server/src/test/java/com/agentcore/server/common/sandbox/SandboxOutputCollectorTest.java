package com.agentcore.server.common.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SandboxOutputCollectorTest {

    private final RecordingSink sink = new RecordingSink();

    @Test
    void splitsChunksOnCharacterBoundariesWithinByteLimit() {
        SandboxOutputCollector collector = new SandboxOutputCollector(sink, 4, 100);

        collector.appendStdout("你好吗".getBytes(StandardCharsets.UTF_8));

        assertThat(sink.stdout).containsExactly("你", "好", "吗");
        assertThat(collector.stdout()).isEqualTo("你好吗");
        assertThat(collector.truncated()).isFalse();
    }

    @Test
    void keepsPartialUtf8CharacterBetweenFrames() {
        SandboxOutputCollector collector = new SandboxOutputCollector(sink, 10, 100);
        byte[] bytes = "你".getBytes(StandardCharsets.UTF_8);

        collector.appendStdout(new byte[] {bytes[0], bytes[1]});
        assertThat(sink.stdout).isEmpty();

        collector.appendStdout(new byte[] {bytes[2]});
        assertThat(sink.stdout).containsExactly("你");
        assertThat(collector.stdout()).isEqualTo("你");
    }

    @Test
    void capsStdoutAndStderrTogetherAndStopsForwarding() {
        SandboxOutputCollector collector = new SandboxOutputCollector(sink, 10, 5);

        collector.appendStdout("abcd".getBytes(StandardCharsets.UTF_8));
        collector.appendStderr("ef".getBytes(StandardCharsets.UTF_8));
        collector.appendStdout("gh".getBytes(StandardCharsets.UTF_8));

        assertThat(sink.stdout).containsExactly("abcd");
        assertThat(sink.stderr).containsExactly("e");
        assertThat(collector.stdout()).isEqualTo("abcd");
        assertThat(collector.stderr()).isEqualTo("e");
        assertThat(collector.truncated()).isTrue();
    }

    @Test
    void countsUtf8BytesNotCharacters() {
        SandboxOutputCollector collector = new SandboxOutputCollector(sink, 10, 7);

        collector.appendStdout("你好吗".getBytes(StandardCharsets.UTF_8));

        // 3 + 3 bytes fit; the last 3-byte character is dropped.
        assertThat(collector.stdout()).isEqualTo("你好");
        assertThat(collector.truncated()).isTrue();
    }

    private static final class RecordingSink implements SandboxEventSink {

        private final List<String> stdout = new ArrayList<>();
        private final List<String> stderr = new ArrayList<>();

        @Override
        public void started(String execId) {}

        @Override
        public void phase(String phase) {}

        @Override
        public void stdout(String chunk) {
            stdout.add(chunk);
        }

        @Override
        public void stderr(String chunk) {
            stderr.add(chunk);
        }

        @Override
        public void result(SandboxExecResult result) {}
    }
}
