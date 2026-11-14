package br.com.motorfraude.loadgenerator.scenarios;

/** Uma requisição pronta para enviar: o id (que vai também em {@code Idempotency-Key}), o cenário e o corpo JSON. */
public record PlannedTransaction(String transactionId, Scenario scenario, byte[] body) {}
