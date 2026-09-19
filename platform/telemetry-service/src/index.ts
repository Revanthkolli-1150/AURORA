import type { ProbeExecutionResult } from '@aurora/types';

export interface MetricSample {
  name: string;
  value: number;
  tags: Record<string, string>;
  timestamp: string;
}

export class TelemetryService {
  private recentProbeResults: ProbeExecutionResult[] = [];
  private metricBuffer: MetricSample[] = [];

  public ingestProbeResult(result: ProbeExecutionResult): void {
    this.recentProbeResults.push(result);
    if (this.recentProbeResults.length > 500) {
      this.recentProbeResults.shift();
    }
  }

  public ingestMetric(metric: MetricSample): void {
    this.metricBuffer.push(metric);
    if (this.metricBuffer.length > 2000) {
      this.metricBuffer.shift();
    }
  }

  public getRecentProbes(): ProbeExecutionResult[] {
    return [...this.recentProbeResults];
  }
}

if (process.argv[1]?.endsWith('index.ts') || process.argv[1]?.endsWith('index.js')) {
  console.log('[TELEMETRY] Telemetry Ingestion Service initialized.');
}
