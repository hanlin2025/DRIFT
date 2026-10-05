import { describe, expect, it } from 'vitest';
import { along, locate } from './places';

describe('port locations', () => {
  it('puts Shanghai, Singapore and Jakarta on their coasts', () => {
    const shanghai = locate('Shanghai');
    const singapore = locate('Singapore');
    const jakarta = locate('Jakarta, ID');
    expect(shanghai).not.toBeNull();
    expect(singapore).not.toBeNull();
    expect(jakarta).not.toBeNull();
    expect(shanghai![0]).toBeGreaterThan(singapore![0]);
    expect(shanghai![1]).toBeGreaterThan(singapore![1]);
    expect(jakarta![1]).toBeLessThan(singapore![1]);
  });

  it('puts the mother vessel on the sea between the ports', () => {
    const origin = locate('Shanghai')!;
    const hub = locate('Singapore')!;
    const vessel = along(origin, hub, 0.58);
    expect(vessel[0]).toBeLessThan(origin[0]);
    expect(vessel[0]).toBeGreaterThan(hub[0]);
  });
});
