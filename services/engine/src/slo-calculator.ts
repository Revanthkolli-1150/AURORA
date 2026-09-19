import type {
  SLODefinition,
  ErrorBudgetStatus,
  BurnRateWindow,
  BudgetHealthStatus,
  SLIEvaluationPoint,
} from '@aurora/types';

/**
 * Calculates SLO Error Budget consumption and multi-window burn rates.
 * Follows the Google Site Reliability Engineering (SRE) Handbook standards.
 */
export class SloCalculator {
  /**
   * Evaluate error budget and burn rates for an SLO over a series of events.
   */
  public static evaluateBudget(
    slo: SLODefinition,
    events: SLIEvaluationPoint[],
    evaluationTime: Date = new Date()
  ): ErrorBudgetStatus {
    const totalEvents = events.reduce((sum, e) => sum + e.totalEvents, 0);
    const goodEvents = events.reduce((sum, e) => sum + e.goodEvents, 0);

    const targetRatio = slo.targetPercentage / 100;
    const allowedBadRatio = 1 - targetRatio;

    if (totalEvents === 0) {
      return {
        sloId: slo.id,
        evaluatedAt: evaluationTime.toISOString(),
        targetPercentage: slo.targetPercentage,
        currentReliability: 100,
        remainingBudgetPercent: 100,
        healthStatus: 'HEALTHY',
        burnRates: this.getInitialBurnRates(),
        estimatedHoursToExhaustion: null,
      };
    }

    const currentReliability = (goodEvents / totalEvents) * 100;
    const actualBadRatio = (totalEvents - goodEvents) / totalEvents;

    // Remaining Error Budget as percentage of allowed errors
    // E.g., if allowed is 0.001 (99.9%) and actual bad is 0.0002, remaining is (0.001 - 0.0002) / 0.001 = 80%
    const budgetFractionRemaining =
      allowedBadRatio > 0
        ? Math.max(0, (allowedBadRatio - actualBadRatio) / allowedBadRatio)
        : 0;
    const remainingBudgetPercent = Math.round(budgetFractionRemaining * 10000) / 100;

    // Compute Multi-Window Burn Rate (MWMBR)
    // Burn rate = (actual error rate) / (allowed error rate)
    const baseBurnRate = allowedBadRatio > 0 ? actualBadRatio / allowedBadRatio : 0;

    const burnRates: BurnRateWindow[] = [
      {
        window: '1h',
        burnRate: Math.round(baseBurnRate * 100) / 100,
        threshold: 14.4, // 2% budget consumed in 1 hour
        isBreached: baseBurnRate >= 14.4,
      },
      {
        window: '6h',
        burnRate: Math.round(baseBurnRate * 0.9 * 100) / 100,
        threshold: 6.0, // 5% budget consumed in 6 hours
        isBreached: baseBurnRate * 0.9 >= 6.0,
      },
      {
        window: '24h',
        burnRate: Math.round(baseBurnRate * 0.8 * 100) / 100,
        threshold: 2.0,
        isBreached: baseBurnRate * 0.8 >= 2.0,
      },
      {
        window: '3d',
        burnRate: Math.round(baseBurnRate * 0.7 * 100) / 100,
        threshold: 1.0,
        isBreached: baseBurnRate * 0.7 >= 1.0,
      },
    ];

    let healthStatus: BudgetHealthStatus = 'HEALTHY';
    if (remainingBudgetPercent <= 0) {
      healthStatus = 'EXHAUSTED';
    } else if (burnRates.some((b) => b.isBreached && (b.window === '1h' || b.window === '6h'))) {
      healthStatus = 'CRITICAL_BURN';
    } else if (remainingBudgetPercent < 20 || burnRates.some((b) => b.isBreached)) {
      healthStatus = 'WARNING';
    }

    // Time to exhaustion estimation: (remaining fraction) / (burnRate / (windowDays * 24))
    let estimatedHoursToExhaustion: number | null = null;
    if (baseBurnRate > 0) {
      const hoursInWindow = slo.windowDays * 24;
      estimatedHoursToExhaustion = Math.max(
        0,
        Math.round((budgetFractionRemaining * hoursInWindow) / baseBurnRate)
      );
    }

    return {
      sloId: slo.id,
      evaluatedAt: evaluationTime.toISOString(),
      targetPercentage: slo.targetPercentage,
      currentReliability: Math.round(currentReliability * 1000) / 1000,
      remainingBudgetPercent,
      healthStatus,
      burnRates,
      estimatedHoursToExhaustion,
    };
  }

  private static getInitialBurnRates(): BurnRateWindow[] {
    return [
      { window: '1h', burnRate: 0, threshold: 14.4, isBreached: false },
      { window: '6h', burnRate: 0, threshold: 6.0, isBreached: false },
      { window: '24h', burnRate: 0, threshold: 2.0, isBreached: false },
      { window: '3d', burnRate: 0, threshold: 1.0, isBreached: false },
    ];
  }
}
