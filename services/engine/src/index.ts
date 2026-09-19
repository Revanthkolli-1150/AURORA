import { SloCalculator } from './slo-calculator.js';
import { SyntheticProbeRunner } from './probe-runner.js';
import { ResilienceController } from './resilience-controller.js';
import type { SLODefinition, SyntheticProbe } from '@aurora/types';

export * from './slo-calculator.js';
export * from './probe-runner.js';
export * from './resilience-controller.js';

export class AuroraReliabilityEngine {
  private resilienceController = new ResilienceController();

  constructor() {
    console.log('[AURORA] Reliability Engine Core Initialized.');
  }

  public getResilienceController(): ResilienceController {
    return this.resilienceController;
  }

  public async evaluateHealth(
    slos: SLODefinition[],
    probes: SyntheticProbe[]
  ): Promise<void> {
    console.log(`[AURORA] Running continuous health evaluation across ${slos.length} SLOs and ${probes.length} probes...`);
    for (const probe of probes) {
      if (probe.isActive) {
        const result = await SyntheticProbeRunner.executeProbe(probe);
        console.log(`[PROBE] ${probe.name} -> ${result.status} (${result.durationMs}ms)`);
      }
    }
  }
}

// Self-diagnostic test entry point
if (process.argv[1]?.endsWith('index.ts') || process.argv[1]?.endsWith('index.js')) {
  const engine = new AuroraReliabilityEngine();
  console.log('[AURORA] Engine daemon ready for commands.');
}
