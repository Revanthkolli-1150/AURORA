export * from './resilience-controller.js';

export class RecoveryService {
  constructor() {
    console.log('[RECOVERY] Recovery & Self-Healing Service initialized.');
  }
}

if (process.argv[1]?.endsWith('index.ts') || process.argv[1]?.endsWith('index.js')) {
  new RecoveryService();
}
