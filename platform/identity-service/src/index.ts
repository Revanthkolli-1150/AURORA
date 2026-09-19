export interface UserSession {
  userId: string;
  username: string;
  role: 'VIEWER' | 'OPERATOR' | 'SRE_LEAD' | 'ADMIN';
  token: string;
  expiresAt: string;
}

export class IdentityService {
  private sessions: Map<string, UserSession> = new Map();

  public authenticate(username: string, role: UserSession['role']): UserSession {
    const token = `aurora-token-${Math.random().toString(36).substring(2, 10)}`;
    const session: UserSession = {
      userId: `usr-${Date.now()}`,
      username,
      role,
      token,
      expiresAt: new Date(Date.now() + 3600 * 1000).toISOString(),
    };
    this.sessions.set(token, session);
    return session;
  }

  public validateToken(token: string): UserSession | null {
    const session = this.sessions.get(token);
    if (!session) return null;
    if (new Date(session.expiresAt) < new Date()) {
      this.sessions.delete(token);
      return null;
    }
    return session;
  }
}

if (process.argv[1]?.endsWith('index.ts') || process.argv[1]?.endsWith('index.js')) {
  console.log('[IDENTITY] Identity Service initialized.');
}
