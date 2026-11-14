package br.com.motorfraude.loadgenerator.scenarios;

import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Sorteia o cenário de cada requisição. {@code fraudRatio} e {@code duplicateRatio} são frações do total de requisições;
 * a fração de fraude se divide igualmente entre os quatro cenários de fraude. O resto é {@link Scenario#NORMAL}.
 */
public final class ScenarioPicker {

    private static final List<Scenario> FRAUD = List.of(
            Scenario.ACCOUNT_TAKEOVER, Scenario.BURST, Scenario.SPRAYING, Scenario.BLOCKLISTED_BENEFICIARY);

    private final double fraudRatio;
    private final double duplicateRatio;
    private final RandomGenerator random;

    public ScenarioPicker(double fraudRatio, double duplicateRatio, RandomGenerator random) {
        if (fraudRatio < 0 || fraudRatio > 1) {
            throw new IllegalArgumentException("a proporção de fraude deve estar entre 0 e 1: " + fraudRatio);
        }
        if (duplicateRatio < 0 || duplicateRatio > 1) {
            throw new IllegalArgumentException("a proporção de duplicatas deve estar entre 0 e 1: " + duplicateRatio);
        }
        if (fraudRatio + duplicateRatio > 1) {
            throw new IllegalArgumentException("a soma das proporções não pode passar de 1");
        }
        this.fraudRatio = fraudRatio;
        this.duplicateRatio = duplicateRatio;
        this.random = random;
    }

    public Scenario next() {
        double draw = random.nextDouble();
        if (draw < fraudRatio) {
            return FRAUD.get(random.nextInt(FRAUD.size()));
        }
        if (draw < fraudRatio + duplicateRatio) {
            return Scenario.DUPLICATE;
        }
        return Scenario.NORMAL;
    }
}
