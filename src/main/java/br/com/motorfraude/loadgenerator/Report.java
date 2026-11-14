package br.com.motorfraude.loadgenerator;

import br.com.motorfraude.loadgenerator.scenarios.Scenario;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.HdrHistogram.Histogram;

/**
 * Resultado de uma execução (só a fase de medição; o aquecimento fica de fora).
 *
 * @param targetTps a meta pedida
 * @param duration a duração pedida
 * @param planned requisições planejadas na medição
 * @param sent as que de fato foram enviadas
 * @param completed as que receberam resposta HTTP (qualquer status)
 * @param dropped as descartadas pelo teto de requisições em voo
 * @param errors as que falharam na rede (recusadas, tempo esgotado)
 * @param tpsSent taxa alcançada no envio
 * @param latencyMicros latência em microssegundos, das respostas HTTP, medida a partir do horário planejado
 * @param statuses respostas por status (ex.: {@code 202 ACCEPTED}, {@code 503})
 * @param scenarios requisições enviadas por cenário
 * @param errorKinds erros de rede por tipo (classe e mensagem da exceção), para diagnosticar a causa
 * @param generatorCpuCores CPU média gasta pelo próprio gerador, em núcleos (negativo se não foi possível medir)
 * @param availableCpus núcleos disponíveis à JVM do gerador
 */
public record Report(
        long targetTps,
        Duration duration,
        long planned,
        long sent,
        long completed,
        long dropped,
        long errors,
        double tpsSent,
        Histogram latencyMicros,
        Map<String, Long> statuses,
        Map<Scenario, Long> scenarios,
        Map<String, Long> errorKinds,
        double generatorCpuCores,
        int availableCpus) {

    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");

    /** Latência no percentil dado (0 a 100), em milissegundos; zero se não houve nenhuma resposta. */
    public double latencyMillis(double percentile) {
        return latencyMicros.getValueAtPercentile(percentile) / 1000.0;
    }

    public long latencyCount() {
        return latencyMicros.getTotalCount();
    }

    /** Quantas respostas demoraram mais que {@code millis}. */
    public long latencyCountAbove(double millis) {
        if (latencyMicros.getTotalCount() == 0) {
            return 0;
        }
        return latencyMicros.getCountBetweenValues((long) (millis * 1000) + 1, latencyMicros.getMaxValue());
    }

    public long statusCount(String status) {
        return statuses.getOrDefault(status, 0L);
    }

    public long scenarioCount(Scenario scenario) {
        return scenarios.getOrDefault(scenario, 0L);
    }

    public String format() {
        var text = new StringBuilder();
        text.append("== Resultado do teste de carga ==\n");
        text.append(String.format(PT_BR, "Meta: %d TPS por %d s%n", targetTps, duration.toSeconds()));
        text.append(String.format(PT_BR, "Requisições: planejadas %d | enviadas %d | concluídas %d | descartadas por sobrecarga %d | erros de rede: %d%n",
                planned, sent, completed, dropped, errors));
        text.append(String.format(PT_BR, "TPS alcançado (envio): %.0f%n", tpsSent));
        text.append(generatorCpuCores < 0
                ? "CPU do gerador: n/d\n"
                : String.format(PT_BR, "CPU do gerador: %.1f núcleos em média (de %d disponíveis); perto do total, o gargalo é o gerador%n",
                        generatorCpuCores, availableCpus));
        text.append("Respostas: ").append(joined(statuses)).append('\n');
        if (!errorKinds.isEmpty()) {
            text.append("Erros por tipo: ").append(joined(errorKinds)).append('\n');
        }
        text.append("Cenários enviados: ").append(joined(scenarios.entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().name(), Map.Entry::getValue)))).append('\n');
        text.append("Latência HTTP, medida a partir do horário planejado de cada requisição (modelo aberto):\n");
        text.append(String.format(PT_BR, "  p50: %.1f ms | p90: %.1f ms | p99: %.1f ms | p99.9: %.1f ms | máx: %.1f ms%n",
                latencyMillis(50), latencyMillis(90), latencyMillis(99), latencyMillis(99.9), latencyMillis(100)));
        return text.toString();
    }

    private static String joined(Map<String, Long> counts) {
        if (counts.isEmpty()) {
            return "(nenhuma)";
        }
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .map(e -> e.getKey() + " " + e.getValue())
                .collect(Collectors.joining(" | "));
    }
}
