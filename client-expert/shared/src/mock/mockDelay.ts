// Simulates network latency for mock service calls so loading states are
// exercised the same way they will be once real API calls replace them.
export function mockDelay(ms = 500): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}
