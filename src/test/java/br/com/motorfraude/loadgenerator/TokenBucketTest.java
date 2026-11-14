package br.com.motorfraude.loadgenerator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T021 — controle de taxa. Sem relógio real: o tempo entra como parâmetro. */
class TokenBucketTest {

    private static final long SECOND = 1_000_000_000L;

    @Test
    @DisplayName("a primeira permissão (índice 0, planejada para o início) já está disponível no instante zero; 1.501 em 1,5 s a 1.000 TPS")
    void acumulaComOTempo() {
        var bucket = new TokenBucket(1000, 0);

        assertThat(bucket.takeAvailable(0, Long.MAX_VALUE)).isEqualTo(1);
        assertThat(bucket.takeAvailable(SECOND + SECOND / 2, Long.MAX_VALUE)).isEqualTo(1500);
        assertThat(bucket.taken()).isEqualTo(1501);
    }

    @Test
    @DisplayName("não entrega a mesma permissão duas vezes")
    void naoRepete() {
        var bucket = new TokenBucket(1000, 0);

        var first = bucket.takeAvailable(SECOND, Long.MAX_VALUE);
        var again = bucket.takeAvailable(SECOND, Long.MAX_VALUE);

        assertThat(first).isEqualTo(1001);
        assertThat(again).isZero();
    }

    @Test
    @DisplayName("respeita o máximo por chamada, e o resto continua disponível")
    void respeitaMaximo() {
        var bucket = new TokenBucket(1000, 0);

        assertThat(bucket.takeAvailable(SECOND, 300)).isEqualTo(300);
        assertThat(bucket.takeAvailable(SECOND, Long.MAX_VALUE)).isEqualTo(701);
    }

    @Test
    @DisplayName("modelo aberto: depois de uma parada de 2 s as permissões atrasadas são todas devidas (sem teto de rajada)")
    void semTetoDeRajada() {
        var bucket = new TokenBucket(1000, 0);
        bucket.takeAvailable(SECOND / 10, Long.MAX_VALUE);

        var afterStall = bucket.takeAvailable(SECOND / 10 + 2 * SECOND, Long.MAX_VALUE);

        assertThat(afterStall).isEqualTo(2000);
    }

    @Test
    @DisplayName("8.000 TPS por 60 s: um nanossegundo antes do fim, exatamente 480.000 permissões (índices 0 a 479.999)")
    void totalExato() {
        var bucket = new TokenBucket(8000, 0);

        assertThat(bucket.takeAvailable(60 * SECOND - 1, Long.MAX_VALUE)).isEqualTo(480_000);
    }

    @Test
    @DisplayName("tps ímpar (7.777): em 10 s, índices 0 a 77.770 = 77.771, sem erro de arredondamento acumulado")
    void tpsImpar() {
        var bucket = new TokenBucket(7777, 0);
        long total = 0;
        for (int step = 1; step <= 100; step++) {
            total += bucket.takeAvailable(step * SECOND / 10, Long.MAX_VALUE);
        }

        assertThat(total).isEqualTo(77_771);
    }

    @Test
    @DisplayName("horário planejado da requisição i = início + i / tps (independe de quando foi enviada)")
    void horarioPlanejado() {
        var bucket = new TokenBucket(1000, 5 * SECOND);

        assertThat(bucket.plannedNanos(0)).isEqualTo(5 * SECOND);
        assertThat(bucket.plannedNanos(1)).isEqualTo(5 * SECOND + 1_000_000);
        assertThat(bucket.plannedNanos(1000)).isEqualTo(6 * SECOND);
    }

    @Test
    @DisplayName("tempo até a próxima permissão")
    void atePróxima() {
        var bucket = new TokenBucket(1000, 0);

        assertThat(bucket.nanosUntilNext(0)).as("a primeira já é devida no início").isZero();
        bucket.takeAvailable(0, Long.MAX_VALUE);
        assertThat(bucket.nanosUntilNext(0)).isEqualTo(1_000_000);
        bucket.takeAvailable(SECOND, Long.MAX_VALUE);
        assertThat(bucket.nanosUntilNext(SECOND)).isEqualTo(1_000_000);
        assertThat(bucket.nanosUntilNext(SECOND + 2_000_000)).isZero();
    }

    @Test
    @DisplayName("taxa zero ou negativa é erro")
    void taxaInvalida() {
        assertThatThrownBy(() -> new TokenBucket(0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TokenBucket(-5, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
