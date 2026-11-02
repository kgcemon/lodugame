// The parts of geoip-country (no bundled types) that server/country.ts uses.
declare module 'geoip-country' {
  const geoip: {
    lookup(ip: string): { country: string; name: string } | null;
  };
  export default geoip;
}
