import http from 'node:http';

const PORT = parseInt(process.env.GATEWAY_PORT || '8080', 10);

export const server = http.createServer((req, res) => {
  const url = new URL(req.url || '/', `http://${req.headers.host}`);

  // Health check endpoint
  if (url.pathname === '/healthz') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ status: 'HEALTHY', service: 'gateway', timestamp: new Date().toISOString() }));
    return;
  }

  // API Route Dispatcher
  if (url.pathname.startsWith('/api/v1/')) {
    res.writeHead(200, {
      'Content-Type': 'application/json',
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'GET, POST, PUT, DELETE, OPTIONS',
    });
    res.end(JSON.stringify({
      message: 'AURORA Gateway v1 API Entry',
      path: url.pathname,
      routes: [
        '/api/v1/telemetry/*',
        '/api/v1/slos/*',
        '/api/v1/incidents/*',
        '/api/v1/chaos/*'
      ]
    }));
    return;
  }

  res.writeHead(404, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify({ error: 'Not Found', path: url.pathname }));
});

if (process.argv[1]?.endsWith('index.ts') || process.argv[1]?.endsWith('index.js')) {
  server.listen(PORT, () => {
    console.log(`[GATEWAY] Aurora API Gateway listening on port ${PORT}`);
  });
}
