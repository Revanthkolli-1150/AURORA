export type SLIType =
  | 'AVAILABILITY'
  | 'LATENCY'
  | 'ERROR_RATE'
  | 'THROUGHPUT'
  | 'SATURATION';

export type BudgetHealthStatus = 'HEALTHY' | 'WARNING' | 'CRITICAL_BURN' | 'EXHAUSTED';

export interface SLODefinition {
  id: string;
  name: string;
  description: string;
  serviceId: string;
  tier: 'TIER_0' | 'TIER_1' | 'TIER_2';
  sliType: SLIType;
  /** Target percentage, e.g. 99.9% */
  targetPercentage: number;
  /** Rolling window in days (e.g., 30 days) */
  windowDays: number;
  /** Latency threshold in milliseconds if sliType === 'LATENCY' */
  latencyThresholdMs?: number;
  tags: string[];
  createdAt: string;
  updatedAt: string;
}

export interface BurnRateWindow {
  window: '1h' | '6h' | '24h' | '3d';
  burnRate: number;
  threshold: number;
  isBreached: boolean;
}

export interface ErrorBudgetStatus {
  sloId: string;
  evaluatedAt: string;
  targetPercentage: number;
  currentReliability: number;
  remainingBudgetPercent: number;
  healthStatus: BudgetHealthStatus;
  burnRates: BurnRateWindow[];
  /** Estimated hours until budget exhaustion at current 1h burn rate */
  estimatedHoursToExhaustion?: number | null;
}

export interface SLIEvaluationPoint {
  timestamp: string;
  goodEvents: number;
  totalEvents: number;
  successRate: number;
}
