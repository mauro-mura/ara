package io.ara.runtime.strategy;

import io.ara.core.agent.AgentConfig;
import io.ara.core.common.Money;

import java.math.BigDecimal;

/**
 * Shared cost estimation logic to avoid duplication.
 */
final class CostEstimator {
    private static final BigDecimal ONE_THOUSAND = BigDecimal.valueOf(1_000);

    private CostEstimator() {
    }

    static BigDecimal fraction(int tokens) {
        return BigDecimal.valueOf(tokens).divide(ONE_THOUSAND);
    }

    static Money estimateCost(int promptTokens, int outputTokens, AgentConfig config) {
        Money inputRate = config.costInputPer1kTokens();
        Money outputRate = config.costOutputPer1kTokens();
        if (inputRate.amount().signum() == 0 && outputRate.amount().signum() == 0) {
            return Money.zero(config.costCurrency());
        }
        return inputRate.multiply(fraction(promptTokens)).plus(outputRate.multiply(fraction(outputTokens)));
    }

    private static final int APPROX_CHARS_PER_TOKEN = 4;

    static int estimateTokensFromChars(int chars) {
        return chars <= 0 ? 0 : Math.max(1, chars / APPROX_CHARS_PER_TOKEN);
    }
}
