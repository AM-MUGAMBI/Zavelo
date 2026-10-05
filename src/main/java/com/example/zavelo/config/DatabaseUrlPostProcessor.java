package com.example.zavelo.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.Profiles;

import java.util.HashMap;
import java.util.Map;

/**
 * On Render (profile "prod") the app must use PostgreSQL. This lets you give it a single value,
 * DATABASE_URL, and it stops with a clear message if no database is set up, instead of quietly
 * using a temporary one that is wiped every time the server restarts.
 */
public class DatabaseUrlPostProcessor implements EnvironmentPostProcessor, Ordered {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.acceptsProfiles(Profiles.of("prod"))) return;

        String url = environment.getProperty("DATABASE_URL");
        if (url == null || url.isBlank()) {
            String host = environment.getProperty("DB_HOST");
            if (host == null || host.isBlank()) {
                throw new IllegalStateException(
                        "No database is set up. In Render, open your PostgreSQL page, copy the Internal Database URL "
                                + "and add it to this web service as an environment variable named DATABASE_URL.");
            }
            return;   // the separate DB_HOST, DB_PORT, DB_NAME, DB_USER and DB_PASSWORD values are used instead
        }

        DatabaseUrl db = DatabaseUrl.parse(url);
        Map<String, Object> values = new HashMap<>();
        values.put("spring.datasource.url", db.jdbcUrl);
        if (db.username != null) values.put("spring.datasource.username", db.username);
        if (db.password != null) values.put("spring.datasource.password", db.password);
        environment.getPropertySources().addFirst(new MapPropertySource("zavelo-database-url", values));
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
