package com.choruskube.core;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.ResultSet;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ChorusKubeApplicationTest extends BaseTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void contextLoads() {
        assertNotNull(dataSource, "DataSource should be configured");
    }

    @Test
    void flywayMigrationsApplied() throws Exception {
        try (Connection conn = dataSource.getConnection();
                ResultSet rs = conn.createStatement()
                        .executeQuery("SELECT count(*) FROM information_schema.tables"
                                + " WHERE table_schema = 'public'"
                                + " AND table_name = 'node_definition'")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1), "node_definition table should exist after Flyway migration");
        }
    }
}
