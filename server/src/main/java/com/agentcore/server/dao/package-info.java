/**
 * Persistence layer.
 *
 * <p>Only this layer talks to MySQL (and other durable stores). Interfaces are
 * named {@code XxxDao}; implementations live in sub-packages such as
 * {@code dao.jdbc}. No DAO exists yet: persistence lands with M1.
 *
 * <p>Controllers and services must depend on DAO interfaces, never on JDBC/JPA
 * types directly.
 */
package com.agentcore.server.dao;
