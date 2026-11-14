package br.com.motorfraude.loadgenerator.scenarios;

/** Tipo de cada requisição gerada. O nome vai no header {@code x-synthetic-scenario}, para conferência. */
public enum Scenario {
    /** Transação comum: conta do banco legado, device conhecido, valor plausível. */
    NORMAL,
    /** Reenvio de um transactionId já enviado, com o mesmo corpo (retry). */
    DUPLICATE,
    /** Sequestro de conta: device novo, valor alto, canal móvel, beneficiário novo. */
    ACCOUNT_TAKEOVER,
    /** Rajada: muitas transações da mesma conta em poucos segundos. */
    BURST,
    /** Pulverização: a mesma conta paga muitos beneficiários diferentes, valores pequenos. */
    SPRAYING,
    /** Beneficiário que está na lista de bloqueio. */
    BLOCKLISTED_BENEFICIARY;

    public boolean isFraud() {
        return this != NORMAL && this != DUPLICATE;
    }
}
