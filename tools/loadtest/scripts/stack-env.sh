# Shared environment for the ephemeral load-test stack. Source it, do not run it:
#   source tools/loadtest/scripts/stack-env.sh
# Every value points at the throwaway containers from the loadtest compose file
# or at the local fake embedding/rerank sidecar; nothing touches the dev stack.

export LOADTEST_MYSQL_PORT="${LOADTEST_MYSQL_PORT:-23306}"
export LOADTEST_REDIS_PORT="${LOADTEST_REDIS_PORT:-23379}"
export LOADTEST_QDRANT_PORT="${LOADTEST_QDRANT_PORT:-23333}"
export LOADTEST_RABBITMQ_PORT="${LOADTEST_RABBITMQ_PORT:-23673}"
export LOADTEST_RABBITMQ_MANAGEMENT_PORT="${LOADTEST_RABBITMQ_MANAGEMENT_PORT:-23674}"
export LOADTEST_SILO_PORT="${LOADTEST_SILO_PORT:-29000}"
export LOADTEST_SILO_CONSOLE_PORT="${LOADTEST_SILO_CONSOLE_PORT:-29001}"
export LOADTEST_EMBEDDING_PORT="${LOADTEST_EMBEDDING_PORT:-28099}"

# server / datasource
export MYSQL_HOST=127.0.0.1
export SPRING_DATASOURCE_URL="jdbc:mysql://127.0.0.1:${LOADTEST_MYSQL_PORT}/agent_core?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"
export SPRING_DATASOURCE_USERNAME=agent_core
export SPRING_DATASOURCE_PASSWORD=agent_core
export REDIS_HOST=127.0.0.1
export REDIS_PORT="${LOADTEST_REDIS_PORT}"
export RABBITMQ_HOST=127.0.0.1
export RABBITMQ_PORT="${LOADTEST_RABBITMQ_PORT}"
export RABBITMQ_USERNAME=agent_core
export RABBITMQ_PASSWORD=agent_core_dev_only
export RABBITMQ_VHOST=/
export QDRANT_URL="http://127.0.0.1:${LOADTEST_QDRANT_PORT}"
export S3_ENDPOINT="http://127.0.0.1:${LOADTEST_SILO_PORT}"
export S3_ACCESS_KEY=agent_core
export S3_SECRET_KEY=agent_core_dev_only
export S3_BUCKET=agent-core-kb

# auth / model credential encryption. Local ephemeral load-test stack only:
# these throwaway values must never be reused outside tools/loadtest.
export JWT_SECRET="loadtest-jwt-secret-0123456789abcdefghij"
export MODEL_SECRET_KEY="MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="

# gateway wiring
export GATEWAY_URL=http://127.0.0.1:3001
export GATEWAY_PORT=3001
export JAVA_API_URL=http://127.0.0.1:8080
export SANDBOX_API_URL=http://127.0.0.1:8080

# deterministic embedding/rerank sidecar
export EMBEDDING_BASE_URL="http://127.0.0.1:${LOADTEST_EMBEDDING_PORT}/v1"
export EMBEDDING_API_KEY=demo
export EMBEDDING_MODEL=demo-embedding
export EMBEDDING_DIM=1024
export RERANK_BASE_URL="http://127.0.0.1:${LOADTEST_EMBEDDING_PORT}/v1"
export RERANK_API_KEY=demo
export RERANK_MODEL=demo-rerank
export KB_ALLOW_PRIVATE_ENDPOINTS=true

# rate limiting is covered by server tests; the load test raises quotas so the
# measurement reflects concurrency and latency rather than throttling.
export RATE_LIMIT_USER_RPM=6000
export RATE_LIMIT_USER_TPM=100000000
export RATE_LIMIT_AUTH_IP_RPM=10000

# harness
export LOADTEST_BASE_URL=http://127.0.0.1:8080
