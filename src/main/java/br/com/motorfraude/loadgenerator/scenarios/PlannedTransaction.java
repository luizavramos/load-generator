package br.com.motorfraude.loadgenerator.scenarios;

public record PlannedTransaction(String transactionId, Scenario scenario, byte[] body) {}
