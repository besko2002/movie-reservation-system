package com.example.moviereservation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The context starts against a real PostgreSQL and Flyway applied V1__baseline. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ApplicationContextAndFlywayTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void contextLoads() {
        assertThat(applicationContext).isNotNull();
        assertThat(applicationContext.getBean(MovieReservationSystemApplication.class)).isNotNull();
    }

    @Test
    void flywayAppliedBaselineMigrationSuccessfully() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank");

        assertThat(rows).isNotEmpty();
        assertThat(rows)
                .anySatisfy(row -> {
                    assertThat(row.get("version")).isEqualTo("1");
                    assertThat(String.valueOf(row.get("description"))).isEqualToIgnoringCase("baseline");
                    assertThat(row.get("success")).isEqualTo(Boolean.TRUE);
                });
        assertThat(rows).allSatisfy(row -> assertThat(row.get("success")).isEqualTo(Boolean.TRUE));
    }
}
