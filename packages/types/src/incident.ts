export type AlertSeverity = 'SEV0' | 'SEV1' | 'SEV2' | 'SEV3';

export type IncidentStatus = 'TRIGGERED' | 'INVESTIGATING' | 'MITIGATED' | 'RESOLVED';

export interface ReliabilityAlert {
  id: string;
  sloId?: string;
  probeId?: string;
  severity: AlertSeverity;
  title: string;
  summary: string;
  source: 'SLO_BURN_RATE' | 'SYNTHETIC_CANARY' | 'ANOMALY_DETECTION' | 'CHAOS_GUARDRAIL';
  triggeredAt: string;
  acknowledgedAt?: string;
  resolvedAt?: string;
}

export interface ReliabilityIncident {
  id: string;
  title: string;
  severity: AlertSeverity;
  status: IncidentStatus;
  leadResponder?: string;
  affectedServices: string[];
  correlatedAlerts: ReliabilityAlert[];
  burnRateImpact?: number;
  blastRadiusScore: number; // 0 to 100
  startedAt: string;
  mitigatedAt?: string;
  resolvedAt?: string;
  runbookUrl?: string;
}
