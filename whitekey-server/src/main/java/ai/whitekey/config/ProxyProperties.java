package ai.whitekey.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.proxy")
public record ProxyProperties(
        boolean enabled,
        int port,
        String address,
        Duration connectTimeout,
        Duration shutdownTimeout,
        DataSize maxInitialLineLength,
        DataSize maxHeaderSize,
        DataSize maxChunkSize) {

    public ProxyProperties {
        connectTimeout = connectTimeout != null ? connectTimeout : Duration.ofSeconds(15);
        shutdownTimeout = shutdownTimeout != null ? shutdownTimeout : Duration.ofSeconds(2);
        maxInitialLineLength = maxInitialLineLength != null ? maxInitialLineLength : DataSize.ofKilobytes(8);
        maxHeaderSize = maxHeaderSize != null ? maxHeaderSize : DataSize.ofKilobytes(32);
        maxChunkSize = maxChunkSize != null ? maxChunkSize : DataSize.ofKilobytes(16);
    }
}
