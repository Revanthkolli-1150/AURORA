import type { ReliabilityIncident, ReliabilityAlert, AlertSeverity } from '@aurora/types';

export class IncidentService {
  private incidents: Map<string, ReliabilityIncident> = new Map();

  public createIncident(
    title: string,
    severity: AlertSeverity,
    affectedServices: string[],
    alerts: ReliabilityAlert[] = []
  ): ReliabilityIncident {
    const incident: ReliabilityIncident = {
      id: `inc-${Date.now()}`,
      title,
      severity,
      status: 'TRIGGERED',
      affectedServices,
      correlatedAlerts: alerts,
      blastRadiusScore: affectedServices.length * 20,
      startedAt: new Date().toISOString(),
    };
    this.incidents.set(incident.id, incident);
    return incident;
  }

  public listActiveIncidents(): ReliabilityIncident[] {
    return Array.from(this.incidents.values()).filter((i) => i.status !== 'RESOLVED');
  }

  public mitigateIncident(id: string): ReliabilityIncident | undefined {
    const inc = this.incidents.get(id);
    if (inc) {
      inc.status = 'MITIGATED';
      inc.mitigatedAt = new Date().toISOString();
    }
    return inc;
  }
}

if (process.argv[1]?.endsWith('index.ts') || process.argv[1]?.endsWith('index.js')) {
  console.log('[INCIDENTS] Incident Service initialized.');
}
