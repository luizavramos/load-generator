package br.com.motorfraude.loadgenerator.scenarios;

import br.com.motorfraude.contracts.Channel;
import br.com.motorfraude.contracts.Counterparty;
import br.com.motorfraude.contracts.EvaluateTransaction;
import br.com.motorfraude.contracts.JsonCodec;
import br.com.motorfraude.contracts.Money;
import br.com.motorfraude.contracts.TransactionType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.random.RandomGenerator;
import java.util.stream.IntStream;

/**
 * Gera transações sintéticas alinhadas ao banco legado local ({@code infra/bank-db/init.sql}): contas
 * {@code acc_tok_00000001..N}, clientes {@code cus_tok_…} de mesmo número e devices conhecidos
 * {@code dev_ + md5(conta:d)}. Assim, quando o motor enriquecer com o perfil, o "normal" parece normal e os cenários de
 * fraude (device novo, beneficiário novo) realmente desviam.
 *
 * <p>Os cenários que dependem de uma conta receber várias transações juntas (rajada e pulverização) usam "contas quentes":
 * poucas contas, que mudam a cada 10 s. Não é thread-safe: um único laço despachante o usa.
 */
public final class TransactionFactory {

    /**
     * Beneficiários do cenário {@code BLOCKLISTED_BENEFICIARY}. A blocklist do motor (specs 002/004) deve carregar estes
     * tokens para a regra de lista disparar na demonstração.
     */
    public static final List<String> BLOCKLISTED_BENEFICIARIES =
            IntStream.rangeClosed(1, 20).mapToObj(i -> String.format("acc_tok_blk%05d", i)).toList();

    private static final List<String> ISPBS = List.of("60701190", "60746948", "00360305", "90400888", "18236120");
    private static final int HOT_GROUP_SIZE = 5;
    private static final long HOT_WINDOW_SECONDS = 10;
    private static final int HISTORY_SIZE = 5000;

    private final Clock clock;
    private final RandomGenerator random;
    private final String runId;
    private final int accounts;
    private final List<Integer> burstPool;
    private final List<Integer> sprayPool;
    private final PlannedTransaction[] history = new PlannedTransaction[HISTORY_SIZE];
    private int historyCount;
    private long sequence;

    /**
     * @param runId identifica a execução e entra no transactionId, para duas execuções seguidas não colidirem no Redis
     * @param accounts quantas contas existem no banco legado (10.000 localmente)
     */
    public TransactionFactory(Clock clock, RandomGenerator random, String runId, int accounts) {
        if (accounts < 2) {
            throw new IllegalArgumentException("são necessárias ao menos 2 contas");
        }
        this.clock = clock;
        this.random = random;
        this.runId = runId;
        this.accounts = accounts;
        var hot = new ArrayList<Integer>(distinctAccounts(Math.min(100, accounts)));
        int half = Math.max(1, hot.size() / 2);
        this.burstPool = List.copyOf(hot.subList(0, half));
        this.sprayPool = List.copyOf(hot.subList(Math.min(half, hot.size() - 1), hot.size()));
    }

    /** Device que o banco legado cadastra para a conta: {@code dev_ + 16 primeiros caracteres de md5(conta:d)}. */
    public static String knownDevice(String accountId, int index) {
        return "dev_" + md5Hex(accountId + ":" + index).substring(0, 16);
    }

    public PlannedTransaction next(Scenario scenario) {
        return switch (scenario) {
            case NORMAL -> remember(normal(Scenario.NORMAL, randomAccount()));
            case DUPLICATE -> duplicate();
            case ACCOUNT_TAKEOVER -> remember(takeover());
            case BURST -> remember(normal(Scenario.BURST, hotAccount(burstPool)));
            case SPRAYING -> remember(spraying());
            case BLOCKLISTED_BENEFICIARY -> remember(blocklisted());
        };
    }

    // ---------- cenários ----------

    private PlannedTransaction normal(Scenario scenario, int account) {
        return build(scenario, account, normalDevice(account), normalAmount(), randomType(), randomChannel(), randomCounterparty(account));
    }

    private PlannedTransaction takeover() {
        int account = randomAccount();
        long amount = 800_000L + random.nextLong(4_200_001L);
        return build(Scenario.ACCOUNT_TAKEOVER, account, newDevice(), amount, TransactionType.PIX, Channel.MOBILE, newBeneficiary());
    }

    private PlannedTransaction spraying() {
        int account = hotAccount(sprayPool);
        long amount = 1_000L + random.nextLong(4_001L);
        return build(Scenario.SPRAYING, account, normalDevice(account), amount, TransactionType.PIX, Channel.MOBILE, newBeneficiary());
    }

    private PlannedTransaction blocklisted() {
        int account = randomAccount();
        var beneficiary = BLOCKLISTED_BENEFICIARIES.get(random.nextInt(BLOCKLISTED_BENEFICIARIES.size()));
        return build(Scenario.BLOCKLISTED_BENEFICIARY, account, normalDevice(account), normalAmount(), TransactionType.PIX,
                randomChannel(), beneficiary);
    }

    private PlannedTransaction duplicate() {
        if (historyCount == 0) {
            return remember(normal(Scenario.NORMAL, randomAccount()));
        }
        var original = history[random.nextInt(Math.min(historyCount, HISTORY_SIZE))];
        return new PlannedTransaction(original.transactionId(), Scenario.DUPLICATE, original.body());
    }

    // ---------- construção ----------

    private PlannedTransaction build(
            Scenario scenario, int account, String device, long amount, TransactionType type, Channel channel, String counterparty) {
        var transactionId = "tx_" + runId + "_" + (++sequence);
        var accountId = accountToken(account);
        var transaction = new EvaluateTransaction(
                1,
                transactionId,
                accountId,
                "cus_tok_" + String.format("%08d", account),
                type,
                new Money(amount, "BRL"),
                new Counterparty(counterparty, ISPBS.get(random.nextInt(ISPBS.size()))),
                channel,
                device,
                clock.instant().minusMillis(random.nextInt(3000)));
        return new PlannedTransaction(transactionId, scenario, JsonCodec.toBytes(transaction));
    }

    private PlannedTransaction remember(PlannedTransaction planned) {
        history[historyCount % HISTORY_SIZE] = planned;
        historyCount++;
        return planned;
    }

    // ---------- sorteios ----------

    private int randomAccount() {
        return 1 + random.nextInt(accounts);
    }

    private String randomCounterparty(int account) {
        int other = 1 + random.nextInt(accounts - 1);
        return accountToken(other >= account ? other + 1 : other);
    }

    private String normalDevice(int account) {
        return knownDevice(accountToken(account), 1 + random.nextInt(1 + account % 4));
    }

    private String newDevice() {
        return "dev_" + md5Hex("new:" + runId + ":" + sequence).substring(0, 16);
    }

    /** Conta que não existe na base (número acima de {@code accounts}); único em toda a execução. */
    private String newBeneficiary() {
        return accountToken(accounts + 1 + (int) (sequence % 90_000_000L));
    }

    private long normalAmount() {
        long cents = Math.round(Math.exp(9.2 + random.nextGaussian()));
        return Math.max(100L, Math.min(500_000L, cents));
    }

    private TransactionType randomType() {
        int draw = random.nextInt(100);
        return draw < 60 ? TransactionType.PIX : draw < 70 ? TransactionType.TED : draw < 80 ? TransactionType.BOLETO : TransactionType.CARD;
    }

    private Channel randomChannel() {
        int draw = random.nextInt(100);
        return draw < 60 ? Channel.MOBILE : draw < 85 ? Channel.WEB : draw < 95 ? Channel.API : Channel.BRANCH;
    }

    /** O grupo ativo gira a cada 10 s: o mesmo punhado de contas recebe a rajada, e depois outro. */
    private int hotAccount(List<Integer> pool) {
        long window = clock.instant().getEpochSecond() / HOT_WINDOW_SECONDS;
        int groups = Math.max(1, pool.size() / HOT_GROUP_SIZE);
        int from = (int) (window % groups) * HOT_GROUP_SIZE;
        int to = Math.min(pool.size(), from + HOT_GROUP_SIZE);
        return pool.get(from + random.nextInt(to - from));
    }

    private List<Integer> distinctAccounts(int count) {
        var chosen = new HashSet<Integer>();
        var ordered = new ArrayList<Integer>();
        while (ordered.size() < count) {
            int account = randomAccount();
            if (chosen.add(account)) {
                ordered.add(account);
            }
        }
        return ordered;
    }

    private static String accountToken(int number) {
        return "acc_tok_" + String.format("%08d", number);
    }

    private static String md5Hex(String text) {
        try {
            var digest = MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 indisponível", e);
        }
    }
}
