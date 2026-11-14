package br.com.motorfraude.loadgenerator;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Arquivo com os transactionId aceitos ({@code 202 ACCEPTED}), um por linha. É a entrada do auditor da spec 004: tudo o
 * que o receiver aceitou precisa aparecer nas decisões do motor.
 */
public final class AcceptedLog implements Consumer<String>, AutoCloseable {

    private final BufferedWriter writer;

    public AcceptedLog(Path file) throws IOException {
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        this.writer = Files.newBufferedWriter(file);
    }

    @Override
    public synchronized void accept(String transactionId) {
        try {
            writer.write(transactionId);
            writer.newLine();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public synchronized void close() throws IOException {
        writer.close();
    }
}
