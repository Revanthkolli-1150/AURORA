export interface AnomalyScore {
  metricName: string;
  zScore: number;
  isAnomaly: boolean;
  value: number;
  mean: number;
  stdDev: number;
}

export class AnomalyEngine {
  public static calculateZScore(values: number[], targetVal: number): AnomalyScore {
    if (values.length < 2) {
      return { metricName: 'metric', zScore: 0, isAnomaly: false, value: targetVal, mean: targetVal, stdDev: 0 };
    }

    const mean = values.reduce((sum, v) => sum + v, 0) / values.length;
    const variance = values.reduce((sum, v) => sum + Math.pow(v - mean, 2), 0) / (values.length - 1);
    const stdDev = Math.sqrt(variance);

    const zScore = stdDev > 0 ? (targetVal - mean) / stdDev : 0;
    const isAnomaly = Math.abs(zScore) >= 3.0; // 3-sigma rule

    return {
      metricName: 'telemetry_metric',
      zScore: Math.round(zScore * 100) / 100,
      isAnomaly,
      value: targetVal,
      mean: Math.round(mean * 100) / 100,
      stdDev: Math.round(stdDev * 100) / 100,
    };
  }
}

if (process.argv[1]?.endsWith('index.ts') || process.argv[1]?.endsWith('index.js')) {
  console.log('[ANOMALY] Anomaly Detection Engine initialized.');
}
