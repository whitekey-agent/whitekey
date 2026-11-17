package ai.whitekey.config;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Ensures {@code app.database.directory} exists before Hibernate/HikariCP opens the SQLite database file.
 */
public class DatabaseDirectoryInitializer
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private static final String PROPERTY_DATABASE_DIRECTORY = "app.database.directory";

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        applicationContext.addBeanFactoryPostProcessor(
                beanFactory -> ensureDirectoryExists(applicationContext.getEnvironment()));
    }

    private static void ensureDirectoryExists(Environment environment) {
        String configuredDir = environment.getProperty(PROPERTY_DATABASE_DIRECTORY);
        if (configuredDir == null || configuredDir.isBlank()) {
            return;
        }
        try {
            Files.createDirectories(Path.of(configuredDir.trim()));
        } catch (IOException ex) {
            throw new UncheckedIOException(
                    "Failed to create WhiteKey data directory: " + configuredDir, ex);
        }
    }
}
