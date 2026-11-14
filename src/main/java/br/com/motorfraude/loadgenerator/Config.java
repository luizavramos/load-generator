package br.com.motorfraude.loadgenerator;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Parâmetros de uma execução (ver {@link CliParser#USAGE}).
 *
 * @param tps requisições por segundo planejadas
 * @param duration duração da medição
 * @param warmup tempo de aquecimento, antes da medição, à mesma taxa; fica fora do relatório
 * @param fraudRatio fração das requisições que seguem um cenário de fraude
 * @param duplicateRatio fração das requisições que reenviam um transactionId já enviado
 * @param target URL base do receiver, sem barra no fim
 * @param clientId valor do header {@code X-Client-Id}
 * @param seed semente do sorteio (execução reproduzível), ou nula
 * @param accounts quantas contas existem no banco legado
 * @param maxInFlight teto de requisições em voo; acima disso a requisição é descartada e contada
 * @param acceptedLog arquivo onde gravar os transactionId aceitos (ACCEPTED), ou nulo
 * @param requestTimeout prazo de cada requisição
 */
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

    /** Requisições do aquecimento. */
    public long warmupRequests() {
        return tps * warmup.toMillis() / 1000;
    }

    /** Requisições planejadas em toda a execução (aquecimento + medição). */
    public long totalRequests() {
        return tps * (warmup.toMillis() + duration.toMillis()) / 1000;
    }

    /** Requisições da medição. */
    public long measuredRequests() {
        return totalRequests() - warmupRequests();
    }
}
