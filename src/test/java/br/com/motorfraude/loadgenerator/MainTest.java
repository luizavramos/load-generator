package br.com.motorfraude.loadgenerator;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.motorfraude.contracts.ContractAssertions;
import br.com.motorfraude.contracts.EvaluateTransaction;
import br.com.motorfraude.contracts.JsonCodec;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** T023 + T025 — o gerador inteiro contra um servidor HTTP local que imita o receiver. */
class MainTest {

    private record Received(String clientId, String idempotencyKey, String scenario, String contentType, String body) {}

    private HttpServer server;
    private final List<Received> received = Collections.synchronizedList(new ArrayList<>());
    private final Set<String> seenIds = Collections.synchronizedSet(new HashSet<>());
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @BeforeEach
    void startServer() throws Exception {
        // backlog grande: com o padrão, a rajada inicial de conexões novas é recusada pelo servidor de teste (não pelo gerador)
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4096);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/v1/transactions:evaluate", exchange -> {
            var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var headers = exchange.getRequestHeaders();
            received.add(new Received(
                    headers.getFirst("X-Client-Id"),
                    headers.getFirst("Idempotency-Key"),
                    headers.getFirst("x-synthetic-scenario"),
                    headers.getFirst("Content-Type"),
                    body));
            var id = JsonCodec.readTree(body).path("transactionId").asString();
            var status = seenIds.add(id) ? "ACCEPTED" : "DUPLICATE";
            var response = ("{\"transactionId\":\"" + id + "\",\"status\":\"" + status + "\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(202, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private int run(String... args) {
        return Main.run(args, new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private String target() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    @DisplayName("envia tps x duração requisições com os headers certos e corpos válidos, e imprime o resumo")
    void execucaoCompleta(@TempDir Path tmp) throws Exception {
        var acceptedLog = tmp.resolve("aceitas.txt");

        var exit = run("--tps", "200", "--duration", "2s", "--target", target(), "--fraud-ratio", "0.1",
                "--duplicate-ratio", "0.1", "--seed", "7", "--accepted-log", acceptedLog.toString());

        assertThat(exit).isZero();
        // o servidor de teste, no mesmo processo, pode derrubar uma ou outra conexão; o gerador conta isso como erro
        assertThat(received).hasSizeBetween(380, 400);
        var scenarios = new HashSet<String>();
        for (var request : new ArrayList<>(received)) {
            assertThat(request.clientId()).isEqualTo("pix-core");
            assertThat(request.contentType()).startsWith("application/json");
            ContractAssertions.assertProduces("evaluate-transaction.v1", request.body());
            assertThat(request.idempotencyKey())
                    .as("Idempotency-Key igual ao transactionId do corpo")
                    .isEqualTo(JsonCodec.fromJson(request.body(), EvaluateTransaction.class).transactionId());
            scenarios.add(request.scenario());
        }
        assertThat(scenarios).contains("NORMAL", "DUPLICATE").anyMatch(s -> !s.equals("NORMAL") && !s.equals("DUPLICATE"));

        var summary = out.toString(StandardCharsets.UTF_8);
        assertThat(summary).contains("200 TPS").contains("p99").contains("202 ACCEPTED").contains("202 DUPLICATE");

        var loggedIds = Files.readAllLines(acceptedLog);
        assertThat(loggedIds).as("só ids ACCEPTED, sem repetição").doesNotHaveDuplicates();
        assertThat(seenIds).as("tudo o que o log tem, o servidor aceitou").containsAll(loggedIds);
        assertThat(loggedIds.size()).as("o log tem (quase) tudo o que o servidor aceitou").isGreaterThanOrEqualTo((int) (seenIds.size() * 0.95));
    }

    @Test
    @DisplayName("fraude ~0,5% e duplicatas ~1% nas proporções pedidas (H4.2 e H4.3), conferidas pelo header de cenário")
    void proporcoesPeloHeader() {
        var exit = run("--tps", "500", "--duration", "6s", "--target", target(), "--fraud-ratio", "0.05",
                "--duplicate-ratio", "0.10", "--seed", "11");

        assertThat(exit).isZero();
        long total = received.size();
        long duplicates = received.stream().filter(r -> r.scenario().equals("DUPLICATE")).count();
        long fraud = received.stream().filter(r -> !r.scenario().equals("NORMAL") && !r.scenario().equals("DUPLICATE")).count();
        assertThat(total).as(out.toString(StandardCharsets.UTF_8) + err.toString(StandardCharsets.UTF_8))
                .isBetween(2900L, 3000L);
        assertThat(fraud / (double) total).isBetween(0.035, 0.065);
        assertThat(duplicates / (double) total).isBetween(0.08, 0.12);
    }

    @Test
    @DisplayName("argumentos inválidos: código 2, mensagem de erro e uso, e nada é enviado")
    void argumentosInvalidos() {
        var exit = run("--tps", "0", "--duration", "1s", "--target", target());

        assertThat(exit).isEqualTo(2);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("--tps").contains("Uso:");
        assertThat(received).isEmpty();
    }

    @Test
    @DisplayName("--help imprime o uso e sai com 0")
    void ajuda() {
        var exit = run("--help");

        assertThat(exit).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("Uso:").contains("--tps").contains("--duration")
                .contains("--fraud-ratio").contains("--duplicate-ratio").contains("--target");
    }

    @Test
    @DisplayName("alvo fora do ar: não trava, termina com tudo contado como erro de rede")
    void alvoForaDoAr() {
        var exit = run("--tps", "50", "--duration", "1s", "--target", "http://127.0.0.1:1");

        assertThat(exit).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("erros de rede: 50");
    }
}
