import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import { SloCalculator } from './slo-calculator.js';
import type { SLODefinition, SLIEvaluationPoint } from '@aurora/types';

describe('SloCalculator', () => {
  const sampleSlo: SLODefinition = {
    id: 'slo-test-1',
    name: 'Sample Checkout Availability',
    description: 'Availability of checkout endpoint',
    serviceId: 'checkout-service',
    tier: 'TIER_0',
    sliType: 'AVAILABILITY',
    targetPercentage: 99.9,
    windowDays: 30,
    tags: ['checkout', 'critical'],
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
  };

  test('calculates 100% budget when no events recorded', () => {
    const status = SloCalculator.evaluateBudget(sampleSlo, []);
    assert.equal(status.remainingBudgetPercent, 100);
    assert.equal(status.healthStatus, 'HEALTHY');
  });

  test('calculates correct budget when healthy events are recorded', () => {
    const events: SLIEvaluationPoint[] = [
      {
        timestamp: new Date().toISOString(),
        goodEvents: 9995,
        totalEvents: 10000,
        successRate: 0.9995,
      },
    ];

    const status = SloCalculator.evaluateBudget(sampleSlo, events);
    // Allowed bad ratio = 0.001 (10 errors allowed out of 10000)
    // Actual bad ratio = 5 / 10000 = 0.0005
    // Remaining budget = 50%
    assert.equal(status.remainingBudgetPercent, 50);
    assert.equal(status.healthStatus, 'HEALTHY');
  });

  test('marks health as EXHAUSTED when error budget is fully consumed', () => {
    const events: SLIEvaluationPoint[] = [
      {
        timestamp: new Date().toISOString(),
        goodEvents: 9980,
        totalEvents: 10000,
        successRate: 0.998,
      },
    ];

    const status = SloCalculator.evaluateBudget(sampleSlo, events);
    assert.equal(status.remainingBudgetPercent, 0);
    assert.equal(status.healthStatus, 'EXHAUSTED');
  });
});
