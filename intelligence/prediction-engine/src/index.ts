export class PredictionEngine {
  public static forecastBudgetDepletion(
    remainingBudgetPercent: number,
    burnRate1h: number,
    sloWindowDays: number = 30
  ): { hoursRemaining: number | null; willBreachInWindow: boolean } {
    if (burnRate1h <= 0) {
      return { hoursRemaining: null, willBreachInWindow: false };
    }

    const totalHoursInWindow = sloWindowDays * 24;
    // Normalized hours remaining
    const hoursRemaining = Math.max(0, (remainingBudgetPercent / 100) * (totalHoursInWindow / burnRate1h));
    const willBreachInWindow = hoursRemaining <= totalHoursInWindow;

    return {
      hoursRemaining: Math.round(hoursRemaining * 10) / 10,
      willBreachInWindow,
    };
  }
}

if (process.argv[1]?.endsWith('index.ts') || process.argv[1]?.endsWith('index.js')) {
  console.log('[PREDICTION] Prediction Engine initialized.');
}
