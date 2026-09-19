import type { SyntheticProbe, ProbeExecutionResult } from '@aurora/types';

export class SyntheticProbeRunner {
  public static async executeProbe(probe: SyntheticProbe): Promise<ProbeExecutionResult> {
    const startTime = performance.now();
    const resultId = `res-${Date.now()}-${Math.random().toString(36).substring(2, 7)}`;
    const timestamp = new Date().toISOString();

    if (!probe.httpConfig) {
      return {
        id: resultId,
        probeId: probe.id,
        timestamp,
        durationMs: 0,
        status: 'DEGRADED',
        message: 'No HTTP configuration specified',
      };
    }

    try {
      const controller = new AbortController();
      const timeoutHandle = setTimeout(() => controller.abort(), probe.timeoutMs);

      const response = await fetch(probe.httpConfig.url, {
        method: probe.httpConfig.method,
        headers: probe.httpConfig.headers,
        signal: controller.signal,
      });

      clearTimeout(timeoutHandle);
      const durationMs = Math.round(performance.now() - startTime);
      const expectedCodes = probe.httpConfig.expectedStatusCodes ?? [200, 201, 204];
      const isSuccess = expectedCodes.includes(response.status);

      return {
        id: resultId,
        probeId: probe.id,
        timestamp,
        durationMs,
        statusCode: response.status,
        status: isSuccess ? 'UP' : 'DOWN',
        message: isSuccess
          ? `Probe succeeded with HTTP ${response.status} in ${durationMs}ms`
          : `Unexpected status code: ${response.status}`,
      };
    } catch (err: unknown) {
      const durationMs = Math.round(performance.now() - startTime);
      const errorMessage = err instanceof Error ? err.message : 'Unknown network failure';
      return {
        id: resultId,
        probeId: probe.id,
        timestamp,
        durationMs,
        status: 'DOWN',
        message: `Probe failed: ${errorMessage}`,
      };
    }
  }
}
