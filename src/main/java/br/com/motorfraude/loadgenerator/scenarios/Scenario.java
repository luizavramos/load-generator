package br.com.motorfraude.loadgenerator.scenarios;

public enum Scenario {
    NORMAL,
    DUPLICATE,
    ACCOUNT_TAKEOVER,
    BURST,
    SPRAYING,
    BLOCKLISTED_BENEFICIARY;

    public boolean isFraud() {
        return this != NORMAL && this != DUPLICATE;
    }
}
