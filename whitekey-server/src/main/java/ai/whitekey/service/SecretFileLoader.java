package ai.whitekey.service;

import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.function.Supplier;

public final class SecretFileLoader {

    public static final String DEFAULT_DATA_DIR_EXPRESSION = "${app.database.directory:${user.home}/.local/share/whitekey}";
    public static final String OWNER_READ_WRITE_PERMISSIONS = "rw-------";
    public static final String PUBLIC_READ_PERMISSIONS = "rw-r--r--";
    private static final String POSIX_VIEW = "posix";

    record LoadedSecret(String value, boolean generated, boolean temporary) {}

    private SecretFileLoader() {}

    static LoadedSecret loadOrCreate(
            Logger log,
            String label,
            String configuredValue,
            Path filePath,
            Supplier<String> generator) {
        if (configuredValue != null && !configuredValue.isBlank()) {
            log.info("{} loaded from environment/configuration.", label);
            return new LoadedSecret(configuredValue.trim(), false, false);
        }

        String generated = null;
        try {
            if (Files.exists(filePath) && Files.size(filePath) > 0) {
                String loaded = Files.readString(filePath, StandardCharsets.UTF_8).trim();
                log.info("{} loaded from file: {}", label, filePath);
                return new LoadedSecret(loaded, false, false);
            }

            generated = generator.get();
            writeFile(filePath, generated, OWNER_READ_WRITE_PERMISSIONS);

            log.info("Generated new {} and saved to: {}", label, filePath);
            return new LoadedSecret(generated, true, false);
        } catch (IOException error) {
            log.error("Failed to load or save {} at {}", label, filePath, error);
            if (generated == null) {
                generated = generator.get();
            }
            return new LoadedSecret(generated, true, true);
        }
    }

    public static void writeFile(Path path, String content, String posixPerms) throws IOException {
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Files.writeString(path, content, StandardCharsets.UTF_8);
        setPosixPermissions(path, posixPerms);
    }

    public static void setPosixPermissions(Path path, String posixPerms) {
        if (posixPerms != null
                && !posixPerms.isBlank()
                && FileSystems.getDefault().supportedFileAttributeViews().contains(POSIX_VIEW)) {
            try {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(posixPerms));
            } catch (Exception ignored) {
            }
        }
    }
}
