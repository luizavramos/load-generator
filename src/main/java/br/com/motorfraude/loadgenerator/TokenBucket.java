package br.com.motorfraude.loadgenerator;

/**
 * Controle de taxa por permissões (token bucket) <strong>sem teto de rajada</strong>, de propósito.
 *
 * <p>A requisição de índice {@code i} é planejada para {@code início + i / tps} e a permissão {@code i} fica disponível
 * a partir desse instante. Se o gerador atrasar (parada de GC, máquina carregada), as permissões atrasadas continuam
 * devidas e são todas liberadas de uma vez: a carga planejada nunca é descartada em silêncio. Um balde com teto esconderia
 * o atraso e é o caminho para o <em>coordinated omission</em> (a latência é medida a partir do horário planejado, em
 * {@link LoadRunner}).
 *
 * <p>Não é thread-safe: um único laço despachante o usa. O tempo entra por parâmetro (nanossegundos), o que o torna
 * testável sem dormir.
 */
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

    /** Retira até {@code maxTokens} permissões já devidas em {@code nowNanos} e devolve quantas retirou. */
    public long takeAvailable(long nowNanos, long maxTokens) {
        long elapsed = nowNanos - startNanos;
        if (elapsed < 0) {
            return 0;
        }
        // a permissão de índice i vence em i/tps; no instante 'elapsed', vencem os índices 0 .. floor(elapsed*tps/1e9)
        long due = Math.multiplyExact(elapsed, tokensPerSecond) / NANOS_PER_SECOND + 1;
        long available = Math.max(0, due - taken);
        long granted = Math.min(available, maxTokens);
        taken += granted;
        return granted;
    }

    /** Quanto falta, em nanossegundos, para a próxima permissão não retirada vencer (zero se já venceu). */
    public long nanosUntilNext(long nowNanos) {
        return Math.max(0, plannedNanos(taken) - nowNanos);
    }

    /** Horário planejado (em nanossegundos do mesmo relógio) da requisição de índice {@code index}. */
    public long plannedNanos(long index) {
        return startNanos + Math.multiplyExact(index, NANOS_PER_SECOND) / tokensPerSecond;
    }

    public long taken() {
        return taken;
    }
}
