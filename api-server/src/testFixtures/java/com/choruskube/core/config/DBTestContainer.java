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
                .withPassword(PASSWORD)
                // Spring's TestContext cache (default 32 contexts) plus tests that use
                // @MockitoBean (which forces a unique merged config per class) easily
                // creates 30+ live HikariPools concurrently, and every new such test class
                // pushes that count up by one. 300 was once enough headroom; it no longer
                // is, so bump to 500.
                .withCommand("postgres", "-c", "max_connections=500");
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
