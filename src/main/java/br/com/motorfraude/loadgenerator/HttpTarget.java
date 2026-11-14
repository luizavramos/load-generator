package br.com.motorfraude.loadgenerator;

import br.com.motorfraude.loadgenerator.scenarios.PlannedTransaction;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Pattern;

/** Envia as transações ao receiver por HTTP/1.1, com o cliente do JDK. Cada chamada bloqueia só a sua thread virtual. */
public final class HttpTarget implements Target, AutoCloseable {

    private static final Pattern STATUS_FIELD = Pattern.compile("\"status\"\\s*:\\s*\"([A-Z_]+)\"");

    private final HttpClient client;
    private final URI endpoint;
    private final String clientId;
    private final Duration requestTimeout;

    public HttpTarget(Config config) {
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        this.endpoint = URI.create(config.target() + "/v1/transactions:evaluate");
        this.clientId = config.clientId();
        this.requestTimeout = config.requestTimeout();
    }

    @Override
    public Response send(PlannedTransaction planned) throws Exception {
        var request = HttpRequest.newBuilder(endpoint)
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("X-Client-Id", clientId)
                .header("Idempotency-Key", planned.transactionId())
                .header("x-synthetic-scenario", planned.scenario().name())
                .POST(HttpRequest.BodyPublishers.ofByteArray(planned.body()))
                .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.statusCode() == 202 ? statusField(response.body()) : "");
    }

    private static String statusField(String body) {
        var matcher = STATUS_FIELD.matcher(body);
        return matcher.find() ? matcher.group(1) : "";
    }

    @Override
    public void close() {
        client.close();
    }
}
