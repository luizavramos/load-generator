package br.com.motorfraude.loadgenerator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T025 — opções da linha de comando. */
class CliParserTest {

    private static Config parse(String... args) {
        return CliParser.parse(args);
    }

    @Test
    @DisplayName("só com --tps e --duration, os demais valores são os padrões documentados")
    void padroes() {
        var config = parse("--tps", "8000", "--duration", "60s");

        assertThat(config.tps()).isEqualTo(8000);
        assertThat(config.duration()).isEqualTo(Duration.ofSeconds(60));
        assertThat(config.warmup()).isEqualTo(Duration.ZERO);
        assertThat(config.fraudRatio()).isEqualTo(0.005);
        assertThat(config.duplicateRatio()).isEqualTo(0.01);
        assertThat(config.target()).isEqualTo(URI.create("http://localhost:8081"));
        assertThat(config.clientId()).isEqualTo("pix-core");
        assertThat(config.accounts()).isEqualTo(10_000);
        assertThat(config.maxInFlight()).isEqualTo(2000);
        assertThat(config.requestTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(config.seed()).isNull();
        assertThat(config.acceptedLog()).isNull();
    }

    @Test
    @DisplayName("todas as opções")
    void todasAsOpcoes() {
        var config = parse("--tps", "25000", "--duration", "5m", "--warmup", "15s", "--fraud-ratio", "0.02",
                "--duplicate-ratio", "0.1", "--target", "http://receiver:8081", "--client-id", "ted-core",
                "--seed", "42", "--accounts", "500", "--max-in-flight", "100", "--accepted-log", "aceitas.txt");

        assertThat(config.tps()).isEqualTo(25_000);
        assertThat(config.duration()).isEqualTo(Duration.ofMinutes(5));
        assertThat(config.warmup()).isEqualTo(Duration.ofSeconds(15));
        assertThat(config.fraudRatio()).isEqualTo(0.02);
        assertThat(config.duplicateRatio()).isEqualTo(0.1);
        assertThat(config.target()).isEqualTo(URI.create("http://receiver:8081"));
        assertThat(config.clientId()).isEqualTo("ted-core");
        assertThat(config.seed()).isEqualTo(42L);
        assertThat(config.accounts()).isEqualTo(500);
        assertThat(config.maxInFlight()).isEqualTo(100);
        assertThat(config.acceptedLog().toString()).isEqualTo("aceitas.txt");
    }

    @Test
    @DisplayName("durações aceitam ms, s, m e h")
    void duracoes() {
        assertThat(parse("--tps", "1", "--duration", "250ms").duration()).isEqualTo(Duration.ofMillis(250));
        assertThat(parse("--tps", "1", "--duration", "90s").duration()).isEqualTo(Duration.ofSeconds(90));
        assertThat(parse("--tps", "1", "--duration", "2m").duration()).isEqualTo(Duration.ofMinutes(2));
        assertThat(parse("--tps", "1", "--duration", "1h").duration()).isEqualTo(Duration.ofHours(1));
    }

    @Test
    @DisplayName("barra de fim de URL do alvo é ignorada")
    void alvoComBarraNoFim() {
        assertThat(parse("--tps", "1", "--duration", "1s", "--target", "http://x:8081/").target())
                .isEqualTo(URI.create("http://x:8081"));
    }

    @Test
    @DisplayName("--tps e --duration são obrigatórios, e a mensagem diz qual falta")
    void obrigatorios() {
        assertThatThrownBy(() -> parse("--duration", "60s")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("--tps");
        assertThatThrownBy(() -> parse("--tps", "100")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("--duration");
    }

    @Test
    @DisplayName("valores inválidos são erro, com a opção no texto")
    void invalidos() {
        assertThatThrownBy(() -> parse("--tps", "0", "--duration", "1s")).hasMessageContaining("--tps");
        assertThatThrownBy(() -> parse("--tps", "abc", "--duration", "1s")).hasMessageContaining("--tps");
        assertThatThrownBy(() -> parse("--tps", "10", "--duration", "60")).hasMessageContaining("--duration");
        assertThatThrownBy(() -> parse("--tps", "10", "--duration", "0s")).hasMessageContaining("--duration");
        assertThatThrownBy(() -> parse("--tps", "10", "--duration", "1s", "--fraud-ratio", "1.5")).hasMessageContaining("--fraud-ratio");
        assertThatThrownBy(() -> parse("--tps", "10", "--duration", "1s", "--duplicate-ratio", "-0.1")).hasMessageContaining("--duplicate-ratio");
        assertThatThrownBy(() -> parse("--tps", "10", "--duration", "1s", "--fraud-ratio", "0.6", "--duplicate-ratio", "0.5"))
                .hasMessageContaining("soma");
        assertThatThrownBy(() -> parse("--tps", "10", "--duration", "1s", "--target", "nao e url")).hasMessageContaining("--target");
        assertThatThrownBy(() -> parse("--tps", "10", "--duration", "1s", "--accounts", "0")).hasMessageContaining("--accounts");
    }

    @Test
    @DisplayName("opção desconhecida e opção sem valor são erro")
    void desconhecidasESemValor() {
        assertThatThrownBy(() -> parse("--tps", "10", "--duration", "1s", "--foo", "1")).hasMessageContaining("--foo");
        assertThatThrownBy(() -> parse("--tps", "10", "--duration")).hasMessageContaining("--duration");
    }
}
