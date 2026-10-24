package com.agentcore.server.common.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentcore.server.config.SandboxProperties;
import com.agentcore.server.dao.ExecRecordDao;
import com.agentcore.server.domain.ExecRecord;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.model.Container;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SandboxMaintenanceTest {

    private final FakeExecRecordDao recordDao = new FakeExecRecordDao();
    private final SandboxProperties properties = SandboxProperties.defaults();

    @Test
    void removesOnlyLabeledContainersOlderThanTheGraceWindow() {
        List<Container> containers = List.of(
                container("old", Instant.now().minusSeconds(600)),
                container("fresh", Instant.now()));
        List<String> removed = new ArrayList<>();
        SandboxMaintenance maintenance =
                new SandboxMaintenance(recordDao, docker(containers, removed), properties);

        int count = maintenance.removeOrphanContainers();

        assertThat(count).isEqualTo(1);
        assertThat(removed).containsExactly("old");
    }

    @Test
    void staleRecoveryUsesTheMaxTimeoutPlusGraceCutoff() {
        recordDao.staleUpdated = 2;
        SandboxMaintenance maintenance =
                new SandboxMaintenance(recordDao, docker(List.of(), new ArrayList<>()), properties);

        int updated = maintenance.recoverStaleRecords();

        assertThat(updated).isEqualTo(2);
        LocalDateTime expected = LocalDateTime.now(ZoneOffset.UTC).minusSeconds(180);
        assertThat(recordDao.staleCutoff.get())
                .isBetween(expected.minusSeconds(5), expected.plusSeconds(5));
    }

    @Test
    void retentionDeletesRowsOlderThanTheConfiguredDays() {
        SandboxMaintenance maintenance =
                new SandboxMaintenance(recordDao, docker(List.of(), new ArrayList<>()), properties);

        maintenance.purgeExpiredRecords();

        LocalDateTime expected = LocalDateTime.now(ZoneOffset.UTC).minusDays(90);
        assertThat(recordDao.deleteCutoff.get())
                .isBetween(expected.minusSeconds(5), expected.plusSeconds(5));
    }

    @Test
    void dockerFailureDoesNotPropagate() {
        DockerClient broken = proxy(DockerClient.class, (method, args) -> {
            if (method.equals("listContainersCmd")) {
                throw new IllegalStateException("daemon down");
            }
            throw new UnsupportedOperationException(method);
        });
        SandboxMaintenance maintenance = new SandboxMaintenance(recordDao, broken, properties);

        assertThat(maintenance.removeOrphanContainers()).isZero();
    }

    private static DockerClient docker(List<Container> containers, List<String> removed) {
        AtomicReference<RemoveContainerCmd> removeRef = new AtomicReference<>();
        removeRef.set(proxy(RemoveContainerCmd.class, (method, args) -> switch (method) {
            case "withForce", "withRemoveVolumes" -> removeRef.get();
            case "exec" -> null;
            default -> throw new UnsupportedOperationException(method);
        }));
        AtomicReference<ListContainersCmd> listRef = new AtomicReference<>();
        listRef.set(proxy(ListContainersCmd.class, (method, args) -> switch (method) {
            case "withShowAll", "withLabelFilter" -> listRef.get();
            case "exec" -> containers;
            default -> throw new UnsupportedOperationException(method);
        }));
        return proxy(DockerClient.class, (method, args) -> {
            if (method.equals("listContainersCmd")) {
                return listRef.get();
            }
            if (method.equals("removeContainerCmd")) {
                removed.add((String) args[0]);
                return removeRef.get();
            }
            throw new UnsupportedOperationException(method);
        });
    }

    private static Container container(String id, Instant created) {
        Container container = new Container();
        setField(container, "id", id);
        setField(container, "created", created.getEpochSecond());
        return container;
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Handler handler) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[] {type},
                (object, method, args) -> handler.invoke(method.getName(), args));
    }

    @FunctionalInterface
    private interface Handler {
        Object invoke(String method, Object[] args) throws Throwable;
    }

    private static final class FakeExecRecordDao implements ExecRecordDao {

        private int staleUpdated;
        private final AtomicReference<LocalDateTime> staleCutoff = new AtomicReference<>();
        private final AtomicReference<LocalDateTime> deleteCutoff = new AtomicReference<>();

        @Override
        public int insert(ExecRecord record) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int markStarted(String id, LocalDateTime startedAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int settle(
                String id,
                String status,
                Integer exitCode,
                Integer durationMs,
                boolean timedOut,
                boolean truncated,
                boolean oomKilled,
                Long peakMemoryBytes,
                Long cpuTimeMs,
                String stdout,
                String stderr,
                String errorMessage,
                LocalDateTime finishedAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ExecRecord findById(String id) {
            return null;
        }

        @Override
        public int markStaleRunning(
                LocalDateTime before, String errorMessage, LocalDateTime finishedAt) {
            staleCutoff.set(before);
            return staleUpdated;
        }

        @Override
        public int deleteOlderThan(LocalDateTime before) {
            deleteCutoff.set(before);
            return 0;
        }
    }
}
