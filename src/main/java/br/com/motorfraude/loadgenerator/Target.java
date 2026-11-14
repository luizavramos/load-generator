package br.com.motorfraude.loadgenerator;

import br.com.motorfraude.loadgenerator.scenarios.PlannedTransaction;

/** Destino das requisições. Bloqueante: o {@link LoadRunner} chama cada envio numa thread virtual própria. */
public interface Target {

    /**
     * @return a resposta HTTP recebida
     * @throws Exception falha de rede ou tempo esgotado (contada como erro, fora da latência)
     */
    Response send(PlannedTransaction planned) throws Exception;

    /**
     * @param status código HTTP
     * @param detail para 202, o campo {@code status} do corpo ({@code ACCEPTED} ou {@code DUPLICATE}); senão, vazio
     */
    record Response(int status, String detail) {}
}
