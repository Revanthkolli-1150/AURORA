import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import { ResilienceController } from './resilience-controller.js';
import type { ChaosExperiment } from '@aurora/types';

describe('ResilienceController Safety Guardrails', () => {
  test('triggers emergency abort when guardrail threshold is exceeded', () => {
    const controller = new ResilienceController();
    const experiment: ChaosExperiment = {
      id: 'chaos-test-lat',
      name: 'Test Latency Injection',
      description: 'Testing latency injection in staging',
      targetServiceId: 'payment-service',
      targetEnvironment: 'staging',
      faultType: 'LATENCY_INJECTION',
      durationSeconds: 60,
      parameters: { latencyMs: 200 },
      guardrails: [
        {
          metricName: 'http_5xx_rate',
          comparison: 'GREATER_THAN',
          threshold: 0.05,
          evaluationWindowSeconds: 10,
        },
      ],
      state: 'RUNNING',
      executedBy: 'sre-automator',
    };

    controller.registerExperiment(experiment);

    // Telemetry within acceptable threshold
    const nominal = controller.evaluateSafetyGuardrails('chaos-test-lat', {
      http_5xx_rate: 0.01,
    });
    assert.equal(nominal.safe, true);
    assert.equal(experiment.state, 'RUNNING');

    // Telemetry breaching safety threshold
    const breach = controller.evaluateSafetyGuardrails('chaos-test-lat', {
      http_5xx_rate: 0.08,
    });
    assert.equal(breach.safe, false);
    assert.equal(experiment.state, 'ABORTED');
    assert.match(experiment.abortReason || '', /Emergency abort triggered/);
  });
});
