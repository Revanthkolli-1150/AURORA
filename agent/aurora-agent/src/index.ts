import { SyntheticProbeRunner } from './probe-runner.js';
import type { SyntheticProbe } from '@aurora/types';

export * from './probe-runner.js';

export class AuroraAgent {
  private activeProbes: SyntheticProbe[] = [];

  constructor() {
    console.log('[AGENT] Aurora Edge Probing Agent Initialized.');
  }

  public registerProbe(probe: SyntheticProbe): void {
    this.activeProbes.push(probe);
  }

  public async runAll(): Promise<void> {
    for (const probe of this.activeProbes) {
      if (probe.isActive) {
        const result = await SyntheticProbeRunner.executeProbe(probe);
        console.log(`[AGENT] Probe ${probe.name} -> ${result.status} (${result.durationMs}ms)`);
      }
    }
  }
}

if (process.argv[1]?.endsWith('index.ts') || process.argv[1]?.endsWith('index.js')) {
  new AuroraAgent();
}
