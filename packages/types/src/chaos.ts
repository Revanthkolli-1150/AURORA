export type FaultType =
  | 'LATENCY_INJECTION'
  | 'HTTP_ERROR_BURST'
  | 'PACKET_LOSS'
  | 'CPU_STRESS'
  | 'MEMORY_PRESSURE'
  | 'SERVICE_BLACKHOLE';

export type ExperimentState =
  | 'DRAFT'
  | 'SCHEDULED'
  | 'RUNNING'
  | 'COMPLETED'
  | 'ABORTED'
  | 'FAILED';

export interface SafetyGuardrail {
  metricName: string;
  comparison: 'GREATER_THAN' | 'LESS_THAN';
  threshold: number;
  evaluationWindowSeconds: number;
}

export interface ChaosExperiment {
  id: string;
  name: string;
  description: string;
  targetServiceId: string;
  targetEnvironment: 'staging' | 'canary' | 'production';
  faultType: FaultType;
  durationSeconds: number;
  parameters: {
    latencyMs?: number;
    jitterMs?: number;
    errorRatePercent?: number;
    cpuPercentage?: number;
    packetLossPercent?: number;
  };
  guardrails: SafetyGuardrail[];
  state: ExperimentState;
  executedBy: string;
  startedAt?: string;
  completedAt?: string;
  abortReason?: string;
}
