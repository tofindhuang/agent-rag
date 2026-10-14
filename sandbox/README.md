# sandbox/

Docker images for isolated code execution (`run_code` tool, requirements.md FR-3).

- `Dockerfile.python` — Python 3.12 execution image, non-root.

Isolation contract (enforced by the Java executor at run time, not by the
image alone): no network, CPU/memory/pids limits, read-only rootfs with a
tmpfs work dir, non-root UID, capability drop, 30s default timeout. The full
flag list is documented in `Dockerfile.python`.

More languages: tracked as open question O3 in the requirements doc.
