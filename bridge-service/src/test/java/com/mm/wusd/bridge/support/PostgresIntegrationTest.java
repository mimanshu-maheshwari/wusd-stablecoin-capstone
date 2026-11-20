package com.mm.wusd.bridge.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
public abstract class PostgresIntegrationTest {

  protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17")
    .withDatabaseName("wusd_test")
    .withUsername("test")
    .withPassword("test");

  static {
    POSTGRES.start();
  }

  @DynamicPropertySource
  static void registerDatabase(DynamicPropertyRegistry registry) {

    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);

    registry.add("spring.datasource.username", POSTGRES::getUsername);

    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @Autowired
  protected JdbcTemplate jdbc;

  @BeforeEach
  void cleanDatabase() {
    jdbc.update("DELETE FROM mint_operations");
  }
}