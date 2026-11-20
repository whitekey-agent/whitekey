package ai.whitekey.cli.client;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public final class CliConfigDefaults {
    private CliConfigDefaults() {}

    public static final String DEFAULT_SERVER_URL = "http://127.0.0.1:1111";
    public static final String ENV_SERVER_URL = "WHITEKEY_SERVER_URL";
    public static final String ENV_ADMIN_TOKEN = "WHITEKEY_ADMIN_TOKEN";
    public static final String ENV_DATA_DIR = "WHITEKEY_DATA_DIR";
    public static final String ENV_ADMIN_TOKEN_FILE = "WHITEKEY_ADMIN_TOKEN_FILE";
    public static final String ENV_CONNECT_TIMEOUT = "WHITEKEY_CONNECT_TIMEOUT";
    public static final String ENV_REQUEST_TIMEOUT = "WHITEKEY_REQUEST_TIMEOUT";
    public static final String DEFAULT_TOKEN_FILE_NAME = "whitekey_admin_token";
    public static final String DEFAULT_DATA_SUBDIR = ".local/share/whitekey";

    public static Path resolveDefaultTokenFile() {
        String tokenFile = firstNonBlank(System.getenv(ENV_ADMIN_TOKEN_FILE), DEFAULT_TOKEN_FILE_NAME);
        String dataDir = firstNonBlank(System.getenv(ENV_DATA_DIR));
        if (dataDir != null) {
            return Path.of(dataDir).resolve(tokenFile);
        }
        String home = System.getProperty("user.home");
        return home != null ? Path.of(home, DEFAULT_DATA_SUBDIR).resolve(tokenFile) : null;
    }

    public static Duration resolveDuration(String envVar, Duration fallback) {
        String raw = System.getenv(envVar);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String trimmed = raw.trim();
        try {
            return trimmed.startsWith("PT") || trimmed.startsWith("pt")
                    ? Duration.parse(trimmed)
                    : Duration.ofSeconds(Long.parseLong(trimmed));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    public static File resolveConfigFile() {
        List<Path> candidates = new ArrayList<>();
        candidates.add(Path.of(".env"));
        String userHome = System.getProperty("user.home");
        if (userHome != null) {
            candidates.add(Path.of(userHome, ".config/whitekey/.env"));
        }
        String dataDir = firstNonBlank(System.getenv(ENV_DATA_DIR));
        if (dataDir != null) {
            candidates.add(Path.of(dataDir, "whitekey.env"));
        } else if (userHome != null) {
            candidates.add(Path.of(userHome, DEFAULT_DATA_SUBDIR, "whitekey.env"));
        }
        return candidates.stream()
                .map(Path::toFile)
                .filter(f -> f.isFile() && f.canRead())
                .findFirst()
                .orElseGet(() -> Path.of(".env").toFile());
    }

    public static String resolveServerUrl(String explicitUrl) {
        return firstNonBlank(explicitUrl, System.getenv(ENV_SERVER_URL), DEFAULT_SERVER_URL);
    }

    public static String resolveAdminToken(String explicitToken) {
        String token = firstNonBlank(explicitToken, System.getenv(ENV_ADMIN_TOKEN));
        if (token != null) {
            return token;
        }
        Path tokenFile = resolveDefaultTokenFile();
        if (tokenFile != null && Files.exists(tokenFile)) {
            try {
                return firstNonBlank(Files.readString(tokenFile, StandardCharsets.UTF_8));
            } catch (IOException ignored) {
            }
        }
        return null;
    }

    public static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    public static int resolveInt(String envVar, int fallback) {
        String raw = System.getenv(envVar);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
