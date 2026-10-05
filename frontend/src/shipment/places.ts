export type LngLat = [number, number];

const PORTS: Record<string, LngLat> = {
  singapore: [103.82, 1.26],
  jakarta: [106.88, -6.1],
  'tanjung priok': [106.88, -6.1],
  surabaya: [112.73, -7.2],
  belawan: [98.69, 3.79],
  semarang: [110.43, -6.95],
  shanghai: [121.66, 31.33],
  ningbo: [121.85, 29.93],
  shenzhen: [114.27, 22.57],
  'hong kong': [114.17, 22.3],
  guangzhou: [113.67, 22.65],
  qingdao: [120.32, 36.07],
  tianjin: [117.78, 38.98],
  xiamen: [118.07, 24.45],
  dalian: [121.66, 38.93],
  busan: [129.04, 35.1],
  incheon: [126.62, 37.46],
  tokyo: [139.77, 35.62],
  yokohama: [139.65, 35.45],
  nagoya: [136.88, 35.05],
  kobe: [135.2, 34.68],
  osaka: [135.43, 34.65],
  kaohsiung: [120.28, 22.61],
  keelung: [121.74, 25.15],
  'port klang': [101.39, 3],
  klang: [101.39, 3],
  'tanjung pelepas': [103.55, 1.36],
  penang: [100.36, 5.42],
  'laem chabang': [100.88, 13.08],
  bangkok: [100.58, 13.7],
  'ho chi minh': [106.78, 10.76],
  'ho chi minh city': [106.78, 10.76],
  saigon: [106.78, 10.76],
  haiphong: [106.72, 20.86],
  manila: [120.95, 14.58],
  colombo: [79.85, 6.95],
  mumbai: [72.95, 18.95],
  'nhava sheva': [72.95, 18.95],
  mundra: [69.7, 22.74],
  chennai: [80.29, 13.1],
  kolkata: [88.32, 22.55],
  chittagong: [91.82, 22.32],
  karachi: [66.98, 24.84],
  'jebel ali': [55.06, 24.99],
  dubai: [55.06, 24.99],
  salalah: [54.01, 16.94],
  jeddah: [39.17, 21.48],
  'port said': [32.31, 31.26],
  sokhna: [32.35, 29.65],
  rotterdam: [4.05, 51.95],
  antwerp: [4.4, 51.27],
  hamburg: [9.95, 53.54],
  bremerhaven: [8.55, 53.55],
  felixstowe: [1.32, 51.96],
  southampton: [-1.4, 50.9],
  'le havre': [0.12, 49.48],
  valencia: [-0.32, 39.44],
  barcelona: [2.17, 41.35],
  genoa: [8.9, 44.4],
  'gioia tauro': [15.9, 38.45],
  piraeus: [23.62, 37.94],
  algeciras: [-5.43, 36.13],
  marsaxlokk: [14.54, 35.82],
  'los angeles': [-118.27, 33.73],
  'long beach': [-118.22, 33.75],
  oakland: [-122.3, 37.8],
  seattle: [-122.35, 47.6],
  vancouver: [-123.1, 49.29],
  'new york': [-74.1, 40.67],
  newark: [-74.14, 40.68],
  savannah: [-81.1, 32.13],
  houston: [-95.02, 29.73],
  charleston: [-79.93, 32.78],
  norfolk: [-76.29, 36.85],
  colon: [-79.9, 9.35],
  balboa: [-79.57, 8.96],
  cartagena: [-75.53, 10.4],
  santos: [-46.33, -23.96],
  'buenos aires': [-58.37, -34.6],
  callao: [-77.15, -12.05],
  melbourne: [144.92, -37.84],
  sydney: [151.21, -33.86],
  brisbane: [153.17, -27.38],
  fremantle: [115.75, -32.05],
  auckland: [174.78, -36.84],
  durban: [31.02, -29.87],
  'cape town': [18.43, -33.91],
  mombasa: [39.65, -4.06],
  lagos: [3.38, 6.45],
  'dar es salaam': [39.28, -6.83],
};

export function locate(place: string): LngLat | null {
  const key = place.trim().toLowerCase().replace(/^port of\s+/, '').split(',')[0]?.trim() ?? '';
  if (!key) return null;
  return PORTS[key] ?? null;
}

function toRad(degrees: number) {
  return degrees * Math.PI / 180;
}

function toDeg(radians: number) {
  return radians * 180 / Math.PI;
}

export function along(start: LngLat, end: LngLat, t: number): LngLat {
  const φ1 = toRad(start[1]);
  const λ1 = toRad(start[0]);
  const φ2 = toRad(end[1]);
  const λ2 = toRad(end[0]);
  const d = 2 * Math.asin(Math.sqrt(Math.sin((φ2 - φ1) / 2) ** 2 + Math.cos(φ1) * Math.cos(φ2) * Math.sin((λ2 - λ1) / 2) ** 2));
  if (d < 1e-8) return start;
  const a = Math.sin((1 - t) * d) / Math.sin(d);
  const b = Math.sin(t * d) / Math.sin(d);
  const x = a * Math.cos(φ1) * Math.cos(λ1) + b * Math.cos(φ2) * Math.cos(λ2);
  const y = a * Math.cos(φ1) * Math.sin(λ1) + b * Math.cos(φ2) * Math.sin(λ2);
  const z = a * Math.sin(φ1) + b * Math.sin(φ2);
  return [toDeg(Math.atan2(y, x)), toDeg(Math.atan2(z, Math.hypot(x, y)))];
}

export function arc(start: LngLat, end: LngLat, steps = 48): LngLat[] {
  return Array.from({ length: steps + 1 }, (_, index) => along(start, end, index / steps));
}
