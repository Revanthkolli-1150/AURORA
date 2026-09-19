// k6 load test script for Aurora Ingress Gateway
export const options = {
  stages: [
    { duration: '30s', target: 50 },
    { duration: '1m', target: 200 },
    { duration: '30s', target: 0 },
  ],
  thresholds: {
    http_req_duration: ['p(99)<250'],
    http_req_failed: ['rate<0.01'],
  },
};

export default function () {
  // Target: http://localhost:8080/healthz
}
