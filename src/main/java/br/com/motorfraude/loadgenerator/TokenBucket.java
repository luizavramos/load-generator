package br.com.motorfraude.loadgenerator;

public final class TokenBucket {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private final long tokensPerSecond;
    private final long startNanos;
    private long taken;

    public TokenBucket(long tokensPerSecond, long startNanos) {
        if (tokensPerSecond <= 0) {
            throw new IllegalArgumentException("a taxa deve ser positiva: " + tokensPerSecond);
        }
        this.tokensPerSecond = tokensPerSecond;
        this.startNanos = startNanos;
    }

    public long takeAvailable(long nowNanos, long maxTokens) {
        long elapsed = nowNanos - startNanos;
        if (elapsed < 0) {
            return 0;
        }
        long due = Math.multiplyExact(elapsed, tokensPerSecond) / NANOS_PER_SECOND + 1;
        long available = Math.max(0, due - taken);
        long granted = Math.min(available, maxTokens);
        taken += granted;
        return granted;
    }

    public long nanosUntilNext(long nowNanos) {
        return Math.max(0, plannedNanos(taken) - nowNanos);
    }

    public long plannedNanos(long index) {
        return startNanos + Math.multiplyExact(index, NANOS_PER_SECOND) / tokensPerSecond;
    }

    public long taken() {
        return taken;
    }
}
