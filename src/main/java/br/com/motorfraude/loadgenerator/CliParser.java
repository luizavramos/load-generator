package br.com.motorfraude.loadgenerator;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Lê a linha de comando. Qualquer problema vira {@link IllegalArgumentException} com a opção no texto. */
public final class CliParser {

    public static final String USAGE = """
            Uso: load-generator --tps <n> --duration <tempo> [opções]

              --tps <n>                requisições por segundo (obrigatório)
              --duration <tempo>       duração da medição: 250ms, 60s, 5m, 1h (obrigatório)
              --warmup <tempo>         aquecimento antes da medição, à mesma taxa; fica fora do relatório (padrão 0s)
              --fraud-ratio <0..1>     fração de requisições com cenário de fraude (padrão 0.005)
              --duplicate-ratio <0..1> fração de reenvios de transactionId já enviado (padrão 0.01)
              --target <url>           receiver (padrão http://localhost:8081)
              --client-id <id>         header X-Client-Id (padrão pix-core)
              --seed <n>               semente do sorteio, para execução reproduzível
              --accounts <n>           contas do banco legado a usar (padrão 10000)
              --max-in-flight <n>      teto de requisições em voo; acima disso descarta e conta (padrão 2000; na nuvem, use mais)
              --accepted-log <arquivo> grava os transactionId aceitos (ACCEPTED), um por linha
              --help                   mostra este texto

            Exemplo: load-generator --tps 8000 --duration 60s --warmup 15s
            """;

    private static final Pattern DURATION = Pattern.compile("^(\\d+)(ms|s|m|h)$");
    private static final Set<String> OPTIONS = Set.of("--tps", "--duration", "--warmup", "--fraud-ratio", "--duplicate-ratio",
            "--target", "--client-id", "--seed", "--accounts", "--max-in-flight", "--accepted-log");

    private CliParser() {}

    public static Config parse(String[] args) {
        Map<String, String> values = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            var option = args[i];
            if (!OPTIONS.contains(option)) {
                throw new IllegalArgumentException("opção desconhecida: " + option);
            }
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException(option + ": falta o valor");
            }
            values.put(option, args[++i]);
        }

        long tps = positiveLong("--tps", required(values, "--tps"));
        var duration = duration("--duration", required(values, "--duration"));
        if (duration.isZero()) {
            throw new IllegalArgumentException("--duration: deve ser maior que zero");
        }
        var warmup = values.containsKey("--warmup") ? duration("--warmup", values.get("--warmup")) : Duration.ZERO;
        double fraud = ratio("--fraud-ratio", values.getOrDefault("--fraud-ratio", "0.005"));
        double duplicate = ratio("--duplicate-ratio", values.getOrDefault("--duplicate-ratio", "0.01"));
        if (fraud + duplicate > 1) {
            throw new IllegalArgumentException("--fraud-ratio + --duplicate-ratio: a soma não pode passar de 1");
        }
        var target = target(values.getOrDefault("--target", "http://localhost:8081"));
        int accounts = (int) positiveLong("--accounts", values.getOrDefault("--accounts", "10000"));
        if (accounts < 2) {
            throw new IllegalArgumentException("--accounts: são necessárias ao menos 2 contas");
        }
        int maxInFlight = (int) positiveLong("--max-in-flight", values.getOrDefault("--max-in-flight", "2000"));
        Long seed = values.containsKey("--seed") ? parseLong("--seed", values.get("--seed")) : null;
        Path acceptedLog = values.containsKey("--accepted-log") ? Path.of(values.get("--accepted-log")) : null;

        return new Config(tps, duration, warmup, fraud, duplicate, target, values.getOrDefault("--client-id", "pix-core"),
                seed, accounts, maxInFlight, acceptedLog, Duration.ofSeconds(5));
    }

    private static String required(Map<String, String> values, String option) {
        var value = values.get(option);
        if (value == null) {
            throw new IllegalArgumentException(option + " é obrigatório");
        }
        return value;
    }

    private static long parseLong(String option, String text) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(option + ": deve ser um número inteiro (recebido: " + text + ")");
        }
    }

    private static long positiveLong(String option, String text) {
        long value = parseLong(option, text);
        if (value <= 0) {
            throw new IllegalArgumentException(option + ": deve ser um inteiro positivo (recebido: " + text + ")");
        }
        return value;
    }

    private static Duration duration(String option, String text) {
        var matcher = DURATION.matcher(text);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(option + ": use um número com unidade (250ms, 60s, 5m, 1h); recebido: " + text);
        }
        long amount = Long.parseLong(matcher.group(1));
        return switch (matcher.group(2)) {
            case "ms" -> Duration.ofMillis(amount);
            case "s" -> Duration.ofSeconds(amount);
            case "m" -> Duration.ofMinutes(amount);
            default -> Duration.ofHours(amount);
        };
    }

    private static double ratio(String option, String text) {
        double value;
        try {
            value = Double.parseDouble(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(option + ": deve ser um número entre 0 e 1 (recebido: " + text + ")");
        }
        if (value < 0 || value > 1) {
            throw new IllegalArgumentException(option + ": deve estar entre 0 e 1 (recebido: " + text + ")");
        }
        return value;
    }

    private static URI target(String text) {
        try {
            var uri = URI.create(text.endsWith("/") ? text.substring(0, text.length() - 1) : text);
            if (uri.getHost() == null || uri.getScheme() == null || !uri.getScheme().startsWith("http")) {
                throw new IllegalArgumentException();
            }
            return uri;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("--target: URL http(s) inválida (recebido: " + text + ")");
        }
    }
}
