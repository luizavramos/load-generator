package br.com.motorfraude.loadgenerator;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.motorfraude.loadgenerator.Target.Response;
import br.com.motorfraude.loadgenerator.scenarios.PlannedTransaction;
import br.com.motorfraude.loadgenerator.scenarios.Scenario;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T021 — taxa, medição em modelo aberto (sem *coordinated omission*), duplicatas, aquecimento e sobrecarga, com alvos
 * simulados. Usa tempo real, com durações curtas.
 */
class LoadRunnerTest {

    private static Config config(String... extra) {
        var args = new ArrayList<>(List.of("--tps", "500", "--duration", "2s"));
        args.addAll(List.of(extra));
        return CliParser.parse(args.toArray(String[]::new));
    }

    private static final Target INSTANT = planned -> new Response(202, "ACCEPTED");

    @Test
    @DisplayName("envia exatamente tps x duração requisições, e o TPS alcançado fica dentro de ±5% (H4.1)")
    void taxaRespeitada() throws Exception {
        var report = new LoadRunner(config(), INSTANT).run();

        assertThat(report.planned()).isEqualTo(1000);
        assertThat(report.sent()).isEqualTo(1000);
        assertThat(report.completed()).isEqualTo(1000);
        assertThat(report.dropped()).isZero();
        assertThat(report.tpsSent()).isBetween(475.0, 525.0);
    }

    @Test
    @DisplayName("modelo aberto: um alvo que trava 1 s para todos NÃO esconde o atraso das requisições planejadas nesse intervalo")
    void semCoordinatedOmission() throws Exception {
        var t0 = new AtomicLong(0);
        Target stalling = planned -> {
            t0.compareAndSet(0, System.nanoTime());
            long releaseAt = t0.get() + 1_000_000_000L;
            long wait = releaseAt - System.nanoTime();
            if (wait > 0) {
                Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
            }
            return new Response(202, "ACCEPTED");
        };
        var report = new LoadRunner(config("--tps", "200"), stalling).run();

        // 400 planejadas, nenhuma omitida; as ~200 do primeiro segundo esperaram até o fim da trava
        assertThat(report.completed()).isEqualTo(400);
        assertThat(report.latencyCount()).isEqualTo(400);
        var slowerThanHalfSecond = report.latencyCountAbove(500);
        assertThat(slowerThanHalfSecond).as("planejadas nos primeiros 0,5 s esperaram mais de 0,5 s").isBetween(90L, 115L);
        assertThat(report.latencyMillis(99)).as("p99").isGreaterThan(900);
        assertThat(report.latencyMillis(100)).as("máximo").isGreaterThan(950);
        assertThat(report.latencyMillis(50)).as("mediana: depois da trava a latência volta ao normal").isLessThan(300);
    }

    @Test
    @DisplayName("a latência conta a partir do horário planejado, não do envio: o atraso do próprio gerador entra na medida")
    void latenciaVemDoHorarioPlanejado() throws Exception {
        // Alvo que bloqueia a thread de quem chama por 1 s só na PRIMEIRA chamada. Em modelo fechado, tudo atrás
        // dela esperaria sem ser medido; em modelo aberto, as demais seguem em outras threads e a lenta é medida inteira.
        var first = new AtomicLong(0);
        Target oneSlow = planned -> {
            if (first.getAndIncrement() == 0) {
                Thread.sleep(1000);
            }
            return new Response(202, "ACCEPTED");
        };

        var report = new LoadRunner(config("--tps", "200"), oneSlow).run();

        assertThat(report.completed()).isEqualTo(400);
        assertThat(report.latencyMillis(100)).isGreaterThan(950);
        assertThat(report.latencyMillis(50)).as("as outras 399 não ficam presas atrás da lenta").isLessThan(50);
    }

    @Test
    @DisplayName("duplicatas (H4.3): reenviam o mesmo transactionId com o mesmo corpo, marcadas como DUPLICATE")
    void duplicatas() throws Exception {
        var seen = Collections.synchronizedList(new ArrayList<PlannedTransaction>());
        Target recording = planned -> {
            seen.add(planned);
            return new Response(202, "ACCEPTED");
        };

        var report = new LoadRunner(config("--tps", "400", "--duration", "1s", "--duplicate-ratio", "0.5", "--fraud-ratio", "0", "--seed", "42"), recording).run();

        var firstBodyById = new HashMap<String, String>();
        int duplicates = 0;
        for (var planned : new ArrayList<>(seen)) {
            if (planned.scenario() == Scenario.DUPLICATE) {
                duplicates++;
                assertThat(firstBodyById).containsKey(planned.transactionId());
                assertThat(new String(planned.body())).isEqualTo(firstBodyById.get(planned.transactionId()));
            } else {
                assertThat(firstBodyById.put(planned.transactionId(), new String(planned.body()))).as("id novo repetido").isNull();
            }
        }
        assertThat(duplicates).isBetween(150, 250);
        assertThat(report.scenarioCount(Scenario.DUPLICATE)).isEqualTo(duplicates);
    }

    @Test
    @DisplayName("o aquecimento envia a carga mas fica fora do relatório")
    void aquecimento() throws Exception {
        var calls = new AtomicLong();
        Target counting = planned -> {
            calls.incrementAndGet();
            return new Response(202, "ACCEPTED");
        };

        var report = new LoadRunner(config("--tps", "200", "--duration", "1s", "--warmup", "1s"), counting).run();

        assertThat(calls.get()).isEqualTo(400);
        assertThat(report.planned()).isEqualTo(200);
        assertThat(report.sent()).isEqualTo(200);
        assertThat(report.latencyCount()).isEqualTo(200);
    }

    @Test
    @DisplayName("respostas por status e erros de rede são contados, e erro não entra no histograma de latência")
    void statusEErros() throws Exception {
        var n = new AtomicLong();
        Target mixed = planned -> {
            long i = n.getAndIncrement();
            if (i % 10 == 0) {
                throw new IOException("conexão recusada");
            }
            return i % 10 == 1 ? new Response(503, "") : new Response(202, i % 2 == 0 ? "ACCEPTED" : "DUPLICATE");
        };

        var report = new LoadRunner(config("--tps", "100", "--duration", "1s", "--fraud-ratio", "0", "--duplicate-ratio", "0"), mixed).run();

        assertThat(report.errors()).isEqualTo(10);
        assertThat(report.statusCount("503")).isEqualTo(10);
        assertThat(report.statusCount("202 ACCEPTED") + report.statusCount("202 DUPLICATE")).isEqualTo(80);
        assertThat(report.latencyCount()).as("só respostas HTTP entram na latência").isEqualTo(90);
    }

    @Test
    @DisplayName("sobrecarga: com o alvo parado, o limite de requisições em voo descarta e conta, sem crescer sem limite")
    void sobrecarga() throws Exception {
        var release = new CountDownLatch(1);
        Target hanging = planned -> {
            release.await();
            return new Response(202, "ACCEPTED");
        };
        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            release.countDown();
        });

        var report = new LoadRunner(config("--tps", "400", "--duration", "1s", "--max-in-flight", "20"), hanging).run();

        assertThat(report.dropped()).isGreaterThan(300);
        assertThat(report.sent() + report.dropped()).isEqualTo(report.planned());
        assertThat(report.sent()).isBetween(20L, 60L);
    }

    @Test
    @DisplayName("ids aceitos (ACCEPTED) vão para o destino configurado; DUPLICATE e erros não")
    void idsAceitos() throws Exception {
        var accepted = Collections.synchronizedSet(new HashSet<String>());
        var n = new AtomicLong();
        Target alternating = planned -> n.getAndIncrement() % 2 == 0 ? new Response(202, "ACCEPTED") : new Response(202, "DUPLICATE");

        new LoadRunner(config("--tps", "100", "--duration", "1s", "--fraud-ratio", "0", "--duplicate-ratio", "0"), alternating, accepted::add).run();

        assertThat(accepted).hasSize(50);
    }
}
