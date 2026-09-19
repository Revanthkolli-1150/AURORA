export * from './slo-calculator.js';

export class PolicyService {
  constructor() {
    console.log('[POLICY] Policy Service initialized.');
  }
}

if (process.argv[1]?.endsWith('index.ts') || process.argv[1]?.endsWith('index.js')) {
  new PolicyService();
}
