export interface ServiceNode {
  id: string;
  errorCount: number;
  dependencies: string[];
}

export class RcaEngine {
  public static findRootCause(nodes: ServiceNode[]): string | null {
    if (nodes.length === 0) return null;
    // Find node with highest errors that is earliest in dependency hierarchy
    const sorted = [...nodes].sort((a, b) => b.errorCount - a.errorCount);
    return sorted[0]?.id ?? null;
  }
}

if (process.argv[1]?.endsWith('index.ts') || process.argv[1]?.endsWith('index.js')) {
  console.log('[RCA] Root Cause Analysis Engine initialized.');
}
