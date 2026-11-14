package br.com.motorfraude.loadgenerator;

import br.com.motorfraude.loadgenerator.scenarios.PlannedTransaction;

public interface Target {

    Response send(PlannedTransaction planned) throws Exception;

    record Response(int status, String detail) {}
}
