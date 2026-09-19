import { useState } from 'react';

interface SLOCardData {
  id: string;
  name: string;
  service: string;
  target: number;
  current: number;
  budgetRemaining: number;
  burnRate1h: number;
  burnRate6h: number;
  status: 'HEALTHY' | 'WARNING' | 'CRITICAL_BURN';
}

interface ProbeCardData {
  id: string;
  name: string;
  region: string;
  latencyMs: number;
  status: 'UP' | 'DEGRADED' | 'DOWN';
  lastChecked: string;
}

interface ChaosCardData {
  id: string;
  name: string;
  fault: string;
  target: string;
  state: 'RUNNING' | 'SCHEDULED' | 'COMPLETED' | 'ABORTED';
  guardrail: string;
}

export default function App() {
  const [activeTab, setActiveTab] = useState<'slos' | 'probes' | 'chaos' | 'incidents'>('slos');
  const [chaosDrillActive, setChaosDrillActive] = useState(false);

  const [slos] = useState<SLOCardData[]>([
    {
      id: 'slo-api-gateway-avail',
      name: 'API Gateway Availability (Tier 0)',
      service: 'ingress-gateway',
      target: 99.95,
      current: 99.982,
      budgetRemaining: 78.4,
      burnRate1h: 0.85,
      burnRate6h: 0.92,
      status: 'HEALTHY',
    },
    {
      id: 'slo-payment-latency',
      name: 'Payment Processing p99 Latency (<450ms)',
      service: 'checkout-service',
      target: 99.0,
      current: 98.72,
      budgetRemaining: 14.2,
      burnRate1h: 3.4,
      burnRate6h: 2.1,
      status: 'CRITICAL_BURN',
    },
    {
      id: 'slo-auth-token-verify',
      name: 'Authentication Verification Success Rate',
      service: 'iam-auth-broker',
      target: 99.9,
      current: 99.91,
      budgetRemaining: 42.0,
      burnRate1h: 1.1,
      burnRate6h: 1.05,
      status: 'WARNING',
    },
  ]);

  const [probes] = useState<ProbeCardData[]>([
    {
      id: 'probe-us-east',
      name: 'North America Canary [US-East-1]',
      region: 'us-east-1',
      latencyMs: 38,
      status: 'UP',
      lastChecked: '4s ago',
    },
    {
      id: 'probe-eu-central',
      name: 'Europe Primary Canary [EU-Frankfurt]',
      region: 'eu-central-1',
      latencyMs: 82,
      status: 'UP',
      lastChecked: '8s ago',
    },
    {
      id: 'probe-ap-south',
      name: 'Asia Pacific Edge Canary [AP-Mumbai]',
      region: 'ap-south-1',
      latencyMs: 145,
      status: 'DEGRADED',
      lastChecked: '2s ago',
    },
  ]);

  const [chaosExperiments, setChaosExperiments] = useState<ChaosCardData[]>([
    {
      id: 'chaos-db-jitter',
      name: 'Staging DB Egress Latency Injection (+180ms)',
      fault: 'LATENCY_INJECTION',
      target: 'order-database-cluster',
      state: 'RUNNING',
      guardrail: 'Abort if Error Budget drops below 20%',
    },
    {
      id: 'chaos-auth-blackhole',
      name: 'Secondary Auth Replicas Isolation Test',
      fault: 'SERVICE_BLACKHOLE',
      target: 'iam-auth-broker',
      state: 'COMPLETED',
      guardrail: 'Automatic rollback upon HTTP 503 spike',
    },
  ]);

  const triggerEmergencyAbort = () => {
    setChaosExperiments((prev) =>
      prev.map((exp) => (exp.state === 'RUNNING' ? { ...exp, state: 'ABORTED' } : exp))
    );
    setChaosDrillActive(false);
  };

  return (
    <div className="aurora-container">
      {/* Header */}
      <header className="header">
        <div className="logo-group">
          <div className="logo-badge">⚡ AURORA</div>
          <div>
            <h1 className="title">Reliability Platform</h1>
            <p className="subtitle">Enterprise SLO Orchestration & Resilience Command Center</p>
          </div>
        </div>
        <div style={{ display: 'flex', gap: '1rem', alignItems: 'center' }}>
          <span className="badge badge-healthy">
            <span className="live-dot"></span> System Nominal
          </span>
          <button
            id="emergency-abort-btn"
            onClick={triggerEmergencyAbort}
            style={{
              backgroundColor: '#dc2626',
              color: '#ffffff',
              border: 'none',
              padding: '0.5rem 1rem',
              borderRadius: '8px',
              fontWeight: 700,
              cursor: 'pointer',
              display: 'flex',
              alignItems: 'center',
              gap: '0.5rem',
            }}
          >
            🛑 Emergency Chaos Abort
          </button>
        </div>
      </header>

      {/* KPI Highlights */}
      <div className="metrics-grid">
        <div className="glass-panel metric-card">
          <div className="metric-header">
            <span className="metric-title">Global Error Budget Health</span>
            <span className="badge badge-warning">2 at risk</span>
          </div>
          <div className="metric-value" style={{ color: '#38bdf8' }}>
            91.4%
          </div>
          <div className="metric-footer">Aggregate remaining across 24 monitored SLOs</div>
        </div>

        <div className="glass-panel metric-card">
          <div className="metric-header">
            <span className="metric-title">Active Synthetic Canaries</span>
            <span className="badge badge-healthy">100% Probing</span>
          </div>
          <div className="metric-value" style={{ color: '#34d399' }}>
            38 / 40 UP
          </div>
          <div className="metric-footer">Global edge latency avg 54ms</div>
        </div>

        <div className="glass-panel metric-card">
          <div className="metric-header">
            <span className="metric-title">Resilience Experiments</span>
            <span className="badge badge-healthy">Guardrails Armed</span>
          </div>
          <div className="metric-value" style={{ color: '#a78bfa' }}>
            1 Active
          </div>
          <div className="metric-footer">Autonomous safety circuit breaker active</div>
        </div>

        <div className="glass-panel metric-card">
          <div className="metric-header">
            <span className="metric-title">Mean Time to Mitigation (MTTM)</span>
            <span className="badge badge-healthy">-18% vs Last 30d</span>
          </div>
          <div className="metric-value" style={{ color: '#f8fafc' }}>
            6.4m
          </div>
          <div className="metric-footer">Based on 9 mitigated synthetic signals</div>
        </div>
      </div>

      {/* Navigation Tabs */}
      <div style={{ display: 'flex', gap: '0.75rem', marginBottom: '1.5rem' }}>
        {[
          { id: 'slos', label: '📊 SLOs & Error Budgets' },
          { id: 'probes', label: '🛰️ Synthetic Canaries' },
          { id: 'chaos', label: '🧪 Resilience & Chaos Engine' },
        ].map((tab) => (
          <button
            key={tab.id}
            id={`tab-${tab.id}`}
            onClick={() => setActiveTab(tab.id as any)}
            style={{
              padding: '0.65rem 1.25rem',
              borderRadius: '10px',
              border: '1px solid ' + (activeTab === tab.id ? 'var(--accent-indigo)' : 'var(--border-subtle)'),
              background: activeTab === tab.id ? 'rgba(99, 102, 241, 0.2)' : 'var(--bg-card)',
              color: activeTab === tab.id ? '#ffffff' : 'var(--text-secondary)',
              fontWeight: 600,
              cursor: 'pointer',
              transition: 'all 0.2s ease',
            }}
          >
            {tab.label}
          </button>
        ))}
      </div>

      {/* Main Content Areas */}
      {activeTab === 'slos' && (
        <div className="glass-panel" style={{ padding: '1.5rem' }}>
          <h2 style={{ fontSize: '1.15rem', marginBottom: '1rem', fontWeight: 700 }}>
            Tier-0 & Tier-1 Service Level Objectives
          </h2>
          <div style={{ display: 'flex', flexDirection: 'column', gap: '1rem' }}>
            {slos.map((slo) => (
              <div
                key={slo.id}
                style={{
                  padding: '1.25rem',
                  borderRadius: '10px',
                  background: 'rgba(255, 255, 255, 0.02)',
                  border: '1px solid var(--border-subtle)',
                  display: 'grid',
                  gridTemplateColumns: '2fr 1fr 1fr 1fr 1fr',
                  alignItems: 'center',
                  gap: '1rem',
                }}
              >
                <div>
                  <div style={{ fontWeight: 600, color: '#f8fafc' }}>{slo.name}</div>
                  <div style={{ fontSize: '0.8rem', color: 'var(--text-muted)' }}>Service: {slo.service}</div>
                </div>
                <div>
                  <div style={{ fontSize: '0.75rem', color: 'var(--text-secondary)' }}>Current / Target</div>
                  <div style={{ fontWeight: 700, fontFamily: 'var(--font-mono)' }}>
                    {slo.current}% <span style={{ color: 'var(--text-muted)' }}>/ {slo.target}%</span>
                  </div>
                </div>
                <div>
                  <div style={{ fontSize: '0.75rem', color: 'var(--text-secondary)' }}>Budget Remaining</div>
                  <div style={{ fontWeight: 700, color: slo.budgetRemaining < 20 ? '#ef4444' : '#10b981' }}>
                    {slo.budgetRemaining}%
                  </div>
                </div>
                <div>
                  <div style={{ fontSize: '0.75rem', color: 'var(--text-secondary)' }}>Burn Rate (1h / 6h)</div>
                  <div style={{ fontFamily: 'var(--font-mono)', fontSize: '0.875rem' }}>
                    {slo.burnRate1h}x / {slo.burnRate6h}x
                  </div>
                </div>
                <div style={{ textAlign: 'right' }}>
                  <span
                    className={
                      slo.status === 'HEALTHY'
                        ? 'badge badge-healthy'
                        : slo.status === 'WARNING'
                        ? 'badge badge-warning'
                        : 'badge badge-critical'
                    }
                  >
                    {slo.status}
                  </span>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {activeTab === 'probes' && (
        <div className="glass-panel" style={{ padding: '1.5rem' }}>
          <h2 style={{ fontSize: '1.15rem', marginBottom: '1rem', fontWeight: 700 }}>
            Global Synthetic Canary Probes
          </h2>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(320px, 1fr))', gap: '1rem' }}>
            {probes.map((probe) => (
              <div
                key={probe.id}
                style={{
                  padding: '1.25rem',
                  borderRadius: '10px',
                  background: 'rgba(255, 255, 255, 0.02)',
                  border: '1px solid var(--border-subtle)',
                }}
              >
                <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '0.5rem' }}>
                  <span style={{ fontWeight: 600 }}>{probe.name}</span>
                  <span className={probe.status === 'UP' ? 'badge badge-healthy' : 'badge badge-warning'}>
                    {probe.status}
                  </span>
                </div>
                <div style={{ fontSize: '0.85rem', color: 'var(--text-secondary)', marginBottom: '0.75rem' }}>
                  Region: <code>{probe.region}</code>
                </div>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                  <div style={{ fontFamily: 'var(--font-mono)', fontSize: '1.25rem', fontWeight: 700 }}>
                    {probe.latencyMs} ms
                  </div>
                  <span style={{ fontSize: '0.75rem', color: 'var(--text-muted)' }}>Checked {probe.lastChecked}</span>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {activeTab === 'chaos' && (
        <div className="glass-panel" style={{ padding: '1.5rem' }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1rem' }}>
            <h2 style={{ fontSize: '1.15rem', fontWeight: 700 }}>Resilience & Fault Injection Scenarios</h2>
            <button
              id="inject-chaos-btn"
              onClick={() => setChaosDrillActive(!chaosDrillActive)}
              style={{
                background: 'linear-gradient(135deg, var(--accent-indigo), var(--accent-violet))',
                color: '#fff',
                border: 'none',
                padding: '0.5rem 1rem',
                borderRadius: '8px',
                fontWeight: 600,
                cursor: 'pointer',
              }}
            >
              + Launch Resilience Scenario
            </button>
          </div>
          <div style={{ display: 'flex', flexDirection: 'column', gap: '1rem' }}>
            {chaosExperiments.map((exp) => (
              <div
                key={exp.id}
                style={{
                  padding: '1.25rem',
                  borderRadius: '10px',
                  background: 'rgba(255, 255, 255, 0.02)',
                  border: '1px solid var(--border-subtle)',
                  display: 'flex',
                  justifyContent: 'space-between',
                  alignItems: 'center',
                }}
              >
                <div>
                  <div style={{ fontWeight: 600, fontSize: '1rem' }}>{exp.name}</div>
                  <div style={{ fontSize: '0.8rem', color: 'var(--text-secondary)', marginTop: '0.25rem' }}>
                    Target: <code>{exp.target}</code> &bull; Fault: <code>{exp.fault}</code>
                  </div>
                  <div style={{ fontSize: '0.75rem', color: '#38bdf8', marginTop: '0.35rem' }}>
                    🛡️ Safety Interlock: {exp.guardrail}
                  </div>
                </div>
                <div>
                  <span
                    className={
                      exp.state === 'RUNNING'
                        ? 'badge badge-warning'
                        : exp.state === 'COMPLETED'
                        ? 'badge badge-healthy'
                        : 'badge badge-critical'
                    }
                  >
                    {exp.state}
                  </span>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
