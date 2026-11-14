package br.com.motorfraude.loadgenerator;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.function.Consumer;

/** Linha de comando do gerador de carga. Códigos de saída: 0 ok, 2 argumentos inválidos, 1 falha ao executar. */
public final class Main {

    private Main() {}

    public static void main(String[] args) {
        // UTF-8 explícito: no Windows o Java usaria a codificação nativa do console e os acentos sairiam quebrados
        var out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        var err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
        System.exit(run(args, out, err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        if (Arrays.asList(args).contains("--help") || Arrays.asList(args).contains("-h")) {
            out.println(CliParser.USAGE);
            return 0;
        }
        Config config;
        try {
            config = CliParser.parse(args);
        } catch (IllegalArgumentException e) {
            err.println("Erro: " + e.getMessage());
            err.println();
            err.println(CliParser.USAGE);
            return 2;
        }

        out.printf("Gerador de carga: %d TPS por %d s (aquecimento %d s) contra %s, fraude %.3f, duplicatas %.3f%n",
                config.tps(), config.duration().toSeconds(), config.warmup().toSeconds(), config.target(),
                config.fraudRatio(), config.duplicateRatio());
        try (var target = new HttpTarget(config)) {
            Report report;
            if (config.acceptedLog() == null) {
                report = new LoadRunner(config, target).run();
            } else {
                try (var log = new AcceptedLog(config.acceptedLog())) {
                    Consumer<String> sink = log;
                    report = new LoadRunner(config, target, sink).run();
                }
            }
            out.println();
            out.println(report.format());
            return 0;
        } catch (IOException e) {
            err.println("Falha ao gravar o log de aceitas: " + e.getMessage());
            return 1;
        }
    }
}
