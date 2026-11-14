package br.com.motorfraude.loadgenerator.scenarios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumMap;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T021 — o sorteio de cenários respeita as proporções pedidas (±1% da spec; aqui, ±0,4 ponto com 200 mil sorteios). */
class ScenarioPickerTest {

    private static final int DRAWS = 200_000;

    private static Map<Scenario, Integer> draw(double fraud, double duplicate, long seed) {
        var picker = new ScenarioPicker(fraud, duplicate, new Random(seed));
        var counts = new EnumMap<Scenario, Integer>(Scenario.class);
        for (int i = 0; i < DRAWS; i++) {
            counts.merge(picker.next(), 1, Integer::sum);
        }
        return counts;
    }

    private static double share(Map<Scenario, Integer> counts, Scenario scenario) {
        return counts.getOrDefault(scenario, 0) / (double) DRAWS;
    }

    @Test
    @DisplayName("proporção de fraude e de duplicatas dentro de ±0,4 ponto percentual")
    void proporcoes() {
        var counts = draw(0.10, 0.05, 1);
        var fraud = counts.entrySet().stream().filter(e -> e.getKey().isFraud()).mapToInt(Map.Entry::getValue).sum() / (double) DRAWS;

        assertThat(fraud).isCloseTo(0.10, org.assertj.core.data.Offset.offset(0.004));
        assertThat(share(counts, Scenario.DUPLICATE)).isCloseTo(0.05, org.assertj.core.data.Offset.offset(0.004));
        assertThat(share(counts, Scenario.NORMAL)).isCloseTo(0.85, org.assertj.core.data.Offset.offset(0.004));
    }

    @Test
    @DisplayName("a fração de fraude se divide igualmente entre os quatro cenários")
    void fraudeDivididaIgualmente() {
        var counts = draw(0.20, 0.0, 2);

        for (var scenario : Scenario.values()) {
            if (scenario.isFraud()) {
                assertThat(share(counts, scenario)).as(scenario.name()).isCloseTo(0.05, org.assertj.core.data.Offset.offset(0.004));
            }
        }
    }

    @Test
    @DisplayName("proporção pequena como a da spec (0,5% de fraude, 1% de duplicatas)")
    void proporcoesDaSpec() {
        var counts = draw(0.005, 0.01, 3);
        var fraud = counts.entrySet().stream().filter(e -> e.getKey().isFraud()).mapToInt(Map.Entry::getValue).sum() / (double) DRAWS;

        assertThat(fraud).isCloseTo(0.005, org.assertj.core.data.Offset.offset(0.001));
        assertThat(share(counts, Scenario.DUPLICATE)).isCloseTo(0.01, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    @DisplayName("sem fraude nem duplicatas, só NORMAL")
    void soNormal() {
        var counts = draw(0, 0, 4);

        assertThat(counts).containsOnlyKeys(Scenario.NORMAL);
    }

    @Test
    @DisplayName("mesma semente, mesma sequência (execuções reproduzíveis)")
    void deterministico() {
        var a = new ScenarioPicker(0.3, 0.2, new Random(99));
        var b = new ScenarioPicker(0.3, 0.2, new Random(99));

        for (int i = 0; i < 1000; i++) {
            assertThat(a.next()).isEqualTo(b.next());
        }
    }

    @Test
    @DisplayName("proporções inválidas são erro: negativas ou somando mais que 1")
    void invalidas() {
        assertThatThrownBy(() -> new ScenarioPicker(-0.1, 0, new Random())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScenarioPicker(0, 1.1, new Random())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScenarioPicker(0.6, 0.5, new Random())).isInstanceOf(IllegalArgumentException.class);
    }
}
