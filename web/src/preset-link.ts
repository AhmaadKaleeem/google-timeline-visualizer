const TOKEN_PATTERN = /^[A-Za-z0-9_-]{4,16}$/;

export function parsePresetToken(search: string): string | null {
  const params = new URLSearchParams(search);
  const values = params.getAll('preset');
  if (values.length !== 1) return null;
  return TOKEN_PATTERN.test(values[0] ?? '') ? values[0] : null;
}

export function presetIntentUrl(token: string, browserFallbackUrl: string): string {
  if (!TOKEN_PATTERN.test(token)) throw new Error('Invalid preset token');
  return `intent://preset/${token}#Intent;scheme=timelinevisualizer;package=dev.mahlernim.timelinevisualizer;S.browser_fallback_url=${encodeURIComponent(browserFallbackUrl)};end`;
}
