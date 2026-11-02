import geoip from 'geoip-country';

/**
 * A player's country from their IP address (offline GeoLite2 data, so no IP leaves the
 * server). Two-letter ISO code, or null for private/unknown addresses (the app then shows
 * a globe).
 */
export function countryForIp(ip: string | undefined | null): string | null {
  if (!ip) return null;
  const addr = ip.startsWith('::ffff:') ? ip.slice(7) : ip;
  try {
    const c = geoip.lookup(addr)?.country;
    return c && /^[A-Z]{2}$/.test(c) ? c : null;
  } catch {
    return null;
  }
}
