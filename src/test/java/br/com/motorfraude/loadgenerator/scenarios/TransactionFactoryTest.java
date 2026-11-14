package br.com.motorfraude.loadgenerator.scenarios;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.motorfraude.contracts.Channel;
import br.com.motorfraude.contracts.ContractAssertions;
import br.com.motorfraude.contracts.EvaluateTransaction;
import br.com.motorfraude.contracts.JsonCodec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T022 — transações sintéticas alinhadas ao banco legado (contas 1..10.000) e cenários de fraude. */
class TransactionFactoryTest {

    private static final Instant NOW = Instant.parse("2026-11-14T12:00:00Z");
    private static final int ACCOUNTS = 10_000;

    private static TransactionFactory factory(long seed) {
        return new TransactionFactory(Clock.fixed(NOW, ZoneOffset.UTC), new Random(seed), "run1", ACCOUNTS);
    }

    private static EvaluateTransaction parse(PlannedTransaction planned) {
        return JsonCodec.fromJson(planned.body(), EvaluateTransaction.class);
    }

    private static int accountNumber(String token) {
        return Integer.parseInt(token.substring("acc_tok_".length()));
    }

    private static Set<String> knownDevices(String accountId) {
        int n = accountNumber(accountId);
        var devices = new HashSet<String>();
        for (int d = 1; d <= 1 + n % 4; d++) {
            devices.add(TransactionFactory.knownDevice(accountId, d));
        }
        return devices;
    }

    @Test
    @DisplayName("device conhecido bate com a fórmula do banco legado: 'dev_' + md5(conta:d) com 16 caracteres")
    void deviceIgualAoBancoLegado() {
        assertThat(TransactionFactory.knownDevice("acc_tok_00000001", 1)).isEqualTo("dev_c5e8a3fbe730f33a");
        assertThat(TransactionFactory.knownDevice("acc_tok_00000042", 3)).isEqualTo("dev_6109c7b699f68022");
    }

    @Test
    @DisplayName("todo cenário gera corpo aderente ao contrato evaluate-transaction.v1 (o receiver o aceitaria)")
    void todosOsCenariosRespeitamOContrato() {
        var factory = factory(1);
        for (var scenario : Scenario.values()) {
            for (int i = 0; i < 200; i++) {
                ContractAssertions.assertProduces("evaluate-transaction.v1", factory.next(scenario).body());
            }
        }
    }

    @Test
    @DisplayName("transactionId segue o padrão do contrato e não se repete entre transações novas")
    void idsUnicos() {
        var factory = factory(2);
        var ids = new HashSet<String>();
        for (int i = 0; i < 20_000; i++) {
            var planned = factory.next(Scenario.NORMAL);
            assertThat(planned.transactionId()).matches("^[A-Za-z0-9._:-]{8,64}$");
            assertThat(parse(planned).transactionId()).isEqualTo(planned.transactionId());
            assertThat(ids.add(planned.transactionId())).as("id repetido").isTrue();
        }
    }

    @Test
    @DisplayName("NORMAL: conta do banco legado, device conhecido da conta, valor plausível, horário recente")
    void normal() {
        var factory = factory(3);
        for (int i = 0; i < 500; i++) {
            var tx = parse(factory.next(Scenario.NORMAL));

            assertThat(accountNumber(tx.accountId())).isBetween(1, ACCOUNTS);
            assertThat(tx.customerId()).isEqualTo("cus_tok_" + tx.accountId().substring("acc_tok_".length()));
            assertThat(knownDevices(tx.accountId())).contains(tx.deviceIdHash());
            assertThat(tx.counterparty().accountToken()).isNotEqualTo(tx.accountId());
            assertThat(tx.amount().value()).isBetween(100L, 500_000L);
            assertThat(tx.amount().currency()).isEqualTo("BRL");
            assertThat(tx.occurredAt()).isBetween(NOW.minus(Duration.ofSeconds(5)), NOW);
        }
    }

    @Test
    @DisplayName("ACCOUNT_TAKEOVER: device que a conta nunca usou, valor alto, canal móvel e beneficiário novo")
    void sequestroDeConta() {
        var factory = factory(4);
        for (int i = 0; i < 200; i++) {
            var planned = factory.next(Scenario.ACCOUNT_TAKEOVER);
            var tx = parse(planned);

            assertThat(planned.scenario()).isEqualTo(Scenario.ACCOUNT_TAKEOVER);
            assertThat(knownDevices(tx.accountId())).doesNotContain(tx.deviceIdHash());
            assertThat(tx.amount().value()).isBetween(800_000L, 5_000_000L);
            assertThat(tx.channel()).isEqualTo(Channel.MOBILE);
            assertThat(accountNumber(tx.counterparty().accountToken())).as("beneficiário que não existe na base").isGreaterThan(ACCOUNTS);
        }
    }

    @Test
    @DisplayName("BURST: poucas contas quentes recebem muitas transações no mesmo intervalo")
    void rajada() {
        var factory = factory(5);
        var accounts = new HashSet<String>();
        for (int i = 0; i < 200; i++) {
            var tx = parse(factory.next(Scenario.BURST));
            accounts.add(tx.accountId());
            assertThat(knownDevices(tx.accountId())).contains(tx.deviceIdHash());
        }

        assertThat(accounts).hasSizeBetween(1, 5);
    }

    @Test
    @DisplayName("SPRAYING: poucas contas, valores pequenos e um beneficiário diferente a cada transação")
    void pulverizacao() {
        var factory = factory(6);
        var accounts = new HashSet<String>();
        var beneficiaries = new HashSet<String>();
        for (int i = 0; i < 200; i++) {
            var tx = parse(factory.next(Scenario.SPRAYING));
            accounts.add(tx.accountId());
            beneficiaries.add(tx.counterparty().accountToken());
            assertThat(tx.amount().value()).isBetween(1_000L, 5_000L);
        }

        assertThat(accounts).hasSizeBetween(1, 5);
        assertThat(beneficiaries).hasSize(200);
    }

    @Test
    @DisplayName("BLOCKLISTED_BENEFICIARY: o beneficiário vem da lista conhecida de bloqueados")
    void beneficiarioEmLista() {
        var factory = factory(7);
        for (int i = 0; i < 200; i++) {
            var tx = parse(factory.next(Scenario.BLOCKLISTED_BENEFICIARY));

            assertThat(TransactionFactory.BLOCKLISTED_BENEFICIARIES).contains(tx.counterparty().accountToken());
        }
    }

    @Test
    @DisplayName("as contas quentes mudam com o tempo (rodízio a cada 10 s), e não são sempre as mesmas")
    void rodizioDeContasQuentes() {
        var at = new java.util.concurrent.atomic.AtomicReference<>(NOW);
        var movable = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return at.get(); }
        };
        var factory = new TransactionFactory(movable, new Random(8), "run1", ACCOUNTS);

        var first = new HashSet<String>();
        IntStream.range(0, 100).forEach(i -> first.add(parse(factory.next(Scenario.BURST)).accountId()));
        at.set(NOW.plusSeconds(10));
        var second = new HashSet<String>();
        IntStream.range(0, 100).forEach(i -> second.add(parse(factory.next(Scenario.BURST)).accountId()));

        assertThat(second).isNotEqualTo(first);
        assertThat(second).doesNotContainAnyElementsOf(first);
    }

    @Test
    @DisplayName("DUPLICATE: reenvia um id já enviado, com exatamente o mesmo corpo")
    void duplicata() {
        var factory = factory(9);
        var bodiesById = new java.util.HashMap<String, String>();
        for (int i = 0; i < 51; i++) {
            var planned = factory.next(Scenario.NORMAL);
            bodiesById.put(planned.transactionId(), new String(planned.body()));
        }

        var duplicate = factory.next(Scenario.DUPLICATE);

        assertThat(duplicate.scenario()).isEqualTo(Scenario.DUPLICATE);
        assertThat(bodiesById).containsKey(duplicate.transactionId());
        assertThat(new String(duplicate.body())).isEqualTo(bodiesById.get(duplicate.transactionId()));
    }

    @Test
    @DisplayName("DUPLICATE sem nada enviado ainda vira uma transação NORMAL nova")
    void duplicataSemHistorico() {
        var planned = factory(10).next(Scenario.DUPLICATE);

        assertThat(planned.scenario()).isEqualTo(Scenario.NORMAL);
    }

    @Test
    @DisplayName("mesma semente e mesmo relógio: mesmos corpos (execuções reproduzíveis)")
    void deterministico() {
        var a = factory(11);
        var b = factory(11);
        List<Scenario> scenarios = List.of(Scenario.NORMAL, Scenario.BURST, Scenario.SPRAYING, Scenario.ACCOUNT_TAKEOVER,
                Scenario.BLOCKLISTED_BENEFICIARY, Scenario.DUPLICATE);

        for (int i = 0; i < 300; i++) {
            var scenario = scenarios.get(i % scenarios.size());
            assertThat(new String(a.next(scenario).body())).isEqualTo(new String(b.next(scenario).body()));
        }
    }
}
