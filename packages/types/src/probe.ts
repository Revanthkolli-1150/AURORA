export type ProbeProtocol = 'HTTP' | 'HTTPS' | 'GRPC' | 'WEBSOCKET' | 'TCP';

export type ProbeStatus = 'UP' | 'DOWN' | 'DEGRADED' | 'MAINTENANCE';

export interface HttpProbeConfig {
  url: string;
  method: 'GET' | 'POST' | 'PUT' | 'DELETE' | 'PATCH' | 'HEAD';
  headers?: Record<string, string>;
  body?: string;
  expectedStatusCodes?: number[];
  jsonPathAssertions?: Array<{
    path: string;
    expectedValue: string | number | boolean;
  }>;
}

export interface SyntheticProbe {
  id: string;
  name: string;
  serviceId: string;
  protocol: ProbeProtocol;
  intervalSeconds: number;
  timeoutMs: number;
  targetRegion: string;
  httpConfig?: HttpProbeConfig;
  isActive: boolean;
  createdAt: string;
}

export interface ProbeExecutionResult {
  id: string;
  probeId: string;
  timestamp: string;
  durationMs: number;
  statusCode?: number;
  status: ProbeStatus;
  message?: string;
  dnsLookupMs?: number;
  tlsHandshakeMs?: number;
  firstByteMs?: number;
}
