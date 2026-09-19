import type { ChaosExperiment, SafetyGuardrail } from '@aurora/types';

export interface GuardrailCheckResult {
  passed: boolean;
  guardrail: SafetyGuardrail;
  currentValue: number;
}

/**
 * Controller managing resilience experiments, safety interlocks, and emergency aborts.
 */
export class ResilienceController {
  private activeExperiments: Map<string, ChaosExperiment> = new Map();

  public registerExperiment(experiment: ChaosExperiment): void {
    this.activeExperiments.set(experiment.id, experiment);
  }

  public getActiveExperiment(id: string): ChaosExperiment | undefined {
    return this.activeExperiments.get(id);
  }

  /**
   * Evaluates safety guardrails. If any guardrail breaches, automatically triggers emergency abort.
   */
  public evaluateSafetyGuardrails(
    experimentId: string,
    currentTelemetry: Record<string, number>
  ): { safe: boolean; breaches: GuardrailCheckResult[] } {
    const experiment = this.activeExperiments.get(experimentId);
    if (!experiment || experiment.state !== 'RUNNING') {
      return { safe: true, breaches: [] };
    }

    const breaches: GuardrailCheckResult[] = [];

    for (const guardrail of experiment.guardrails) {
      const metricVal = currentTelemetry[guardrail.metricName];
      if (metricVal === undefined) continue;

      let isBreached = false;
      if (guardrail.comparison === 'GREATER_THAN' && metricVal > guardrail.threshold) {
        isBreached = true;
      } else if (guardrail.comparison === 'LESS_THAN' && metricVal < guardrail.threshold) {
        isBreached = true;
      }

      if (isBreached) {
        breaches.push({
          passed: false,
          guardrail,
          currentValue: metricVal,
        });
      }
    }

    if (breaches.length > 0) {
      experiment.state = 'ABORTED';
      experiment.abortReason = `Emergency abort triggered by safety guardrail breaches: ${breaches
        .map((b) => `${b.guardrail.metricName} reached ${b.currentValue} (threshold: ${b.guardrail.threshold})`)
        .join('; ')}`;
      experiment.completedAt = new Date().toISOString();
      return { safe: false, breaches };
    }

    return { safe: true, breaches: [] };
  }
}
