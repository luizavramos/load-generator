package br.com.motorfraude.loadgenerator;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

public record Config(
        long tps,
        Duration duration,
        Duration warmup,
        double fraudRatio,
        double duplicateRatio,
        URI target,
        String clientId,
        Long seed,
        int accounts,
        int maxInFlight,
        Path acceptedLog,
        Duration requestTimeout) {

    public long warmupRequests() {
        return tps * warmup.toMillis() / 1000;
    }

    public long totalRequests() {
        return tps * (warmup.toMillis() + duration.toMillis()) / 1000;
    }

    public long measuredRequests() {
        return totalRequests() - warmupRequests();
    }
}
