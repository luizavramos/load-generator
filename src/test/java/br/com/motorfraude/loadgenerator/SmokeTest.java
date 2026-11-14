package br.com.motorfraude.loadgenerator;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.motorfraude.contracts.Schemas;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T020b — o módulo existe, compila e enxerga o módulo contracts. */
class SmokeTest {

    @Test
    @DisplayName("o gerador enxerga o contrato evaluate-transaction.v1 do módulo contracts")
    void enxergaOContrato() {
        assertThat(Schemas.names()).contains("evaluate-transaction.v1");
    }
}
