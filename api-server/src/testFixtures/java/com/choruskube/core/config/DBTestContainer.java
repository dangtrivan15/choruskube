package com.choruskube.core.config;

import org.testcontainers.postgresql.PostgreSQLContainer;

public final class DBTestContainer {

    private static final String IMAGE_VERSION = "postgres:17-alpine";
    private static final String DATABASE_NAME = "choruskube_test";
    private static final String USERNAME = "testuser";
    private static final String PASSWORD = "testpassword";

    private static PostgreSQLContainer container = null;

    private DBTestContainer() {}

    public static synchronized void start() {
        if (container != null && container.isRunning()) {
            return;
        }
        container = new PostgreSQLContainer(IMAGE_VERSION)
                .withDatabaseName(DATABASE_NAME)
                .withUsername(USERNAME)
                .withPassword(PASSWORD);
        container.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (container != null && container.isRunning()) {
                container.stop();
            }
        }));
    }

    public static String getJdbcUrl() {
        return container.getJdbcUrl();
    }

    public static String getUsername() {
        return container.getUsername();
    }

    public static String getPassword() {
        return container.getPassword();
    }
}
