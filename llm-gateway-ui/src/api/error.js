/**
 * Extract the user-facing message shared by the admin `{ msg }` and
 * OpenAI-compatible `{ error: { message } }` response protocols.
 */
export function extractGatewayMessage(error, fallback = '网络错误') {
  const body = error?.response?.data ?? error
  return body?.msg || body?.error?.message || error?.message || fallback
}
