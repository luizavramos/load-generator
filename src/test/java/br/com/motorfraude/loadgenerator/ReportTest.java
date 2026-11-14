package br.com.motorfraude.loadgenerator;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.motorfraude.loadgenerator.scenarios.Scenario;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.HdrHistogram.Histogram;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T024 — o relatório final. */
class ReportTest {

    private static Report report() {
        var micros = new Histogram(60_000_000L, 3);
        for (int i = 0; i < 999; i++) {
            micros.recordValue(1_000); // 1 ms
        }
        micros.recordValue(100_000); // 100 ms
        var statuses = new LinkedHashMap<String, Long>();
        statuses.put("202 ACCEPTED", 990L);
        statuses.put("202 DUPLICATE", 9L);
        statuses.put("503", 1L);
        var scenarios = new LinkedHashMap<Scenario, Long>();
        scenarios.put(Scenario.NORMAL, 985L);
        scenarios.put(Scenario.DUPLICATE, 9L);
        scenarios.put(Scenario.BURST, 6L);
        return new Report(8000, Duration.ofSeconds(60), 1000, 1000, 1000, 0, 0, 7998.4, micros, statuses, scenarios, Map.of(), 2.4, 8);
    }

    @Test
    @DisplayName("percentis em milissegundos a partir do histograma em microssegundos")
    void percentis() {
        var report = report();

        assertThat(report.latencyMillis(50)).isEqualTo(1.0, org.assertj.core.data.Offset.offset(0.01));
        assertThat(report.latencyMillis(99)).isEqualTo(1.0, org.assertj.core.data.Offset.offset(0.01));
        assertThat(report.latencyMillis(100)).isEqualTo(100.0, org.assertj.core.data.Offset.offset(0.2));
        assertThat(report.latencyCount()).isEqualTo(1000);
        assertThat(report.latencyCountAbove(50)).isEqualTo(1);
    }

    @Test
    @DisplayName("o texto traz TPS alcançado, totais, respostas por status, cenários e latências p50/p90/p99/p99.9/máx")
    void texto() {
        var text = report().format();

        assertThat(text).contains("8000 TPS");
        assertThat(text).contains("60 s");
        assertThat(text).contains("7998");
        assertThat(text).contains("202 ACCEPTED").contains("990");
        assertThat(text).contains("202 DUPLICATE").contains("503");
        assertThat(text).contains("NORMAL").contains("DUPLICATE").contains("BURST");
        assertThat(text).contains("p50").contains("p90").contains("p99").contains("p99.9").contains("máx");
        assertThat(text).contains("horário planejado");
        assertThat(text).as("o gerador diz quanta CPU gastou, para o leitor saber se ele mesmo foi o gargalo")
                .contains("CPU do gerador").contains("2,4 núcleos").contains("de 8");
    }

    @Test
    @DisplayName("acessores de contagem: status e cenário ausentes valem zero")
    void contagens() {
        var report = report();

        assertThat(report.statusCount("202 ACCEPTED")).isEqualTo(990);
        assertThat(report.statusCount("429")).isZero();
        assertThat(report.scenarioCount(Scenario.BURST)).isEqualTo(6);
        assertThat(report.scenarioCount(Scenario.SPRAYING)).isZero();
    }

    @Test
    @DisplayName("relatório sem nenhuma resposta HTTP não quebra (tudo falhou)")
    void semRespostas() {
        var empty = new Report(100, Duration.ofSeconds(1), 100, 100, 0, 0, 100, 100.0, new Histogram(60_000_000L, 3), Map.of(), Map.of(), Map.of("ConnectException: recusada", 100L), 0.5, 8);

        assertThat(empty.format()).contains("100").contains("erros").contains("Erros por tipo").contains("ConnectException: recusada");
        assertThat(empty.latencyMillis(99)).isZero();
    }
}
