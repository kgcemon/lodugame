import { describe, expect, it } from 'vitest';
import { toMysql } from '../server/db';

/** The PostgreSQL -> MySQL SQL rewrite used by the MySQL adapter (no database needed). */
describe('toMysql', () => {
  it('turns $n into ? and reorders / repeats parameters', () => {
    const r = toMysql('select * from t where a = $2 and b = $1 and c = least($1, x)', ['one', 'two']);
    expect(r.sql).toBe('select * from t where a = ? and b = ? and c = least(?, x)');
    expect(r.params).toEqual(['two', 'one', 'one']);
  });

  it('drops casts, maps ilike and now(), and leaves string literals alone', () => {
    const r = toMysql(`select count(*)::int as n, $1::jsonb from t where x ilike $2 and y = 'a::int $1' and z < now()`, ['{}', '%a%']);
    expect(r.sql).toBe(`select count(*) as n, ? from t where x like ? and y = 'a::int $1' and z < now(6)`);
    expect(r.params).toEqual(['{}', '%a%']);
  });

  it('rewrites on conflict do nothing to insert ignore', () => {
    expect(toMysql('insert into f (a, b) values ($1, $2) on conflict do nothing', [1, 2]).sql).toBe('insert ignore into f (a, b) values (?, ?) ');
    expect(toMysql('insert into s (m) values ($1) on conflict (m) do nothing', [1]).sql).toBe('insert ignore into s (m) values (?) ');
  });

  it('rewrites on conflict do update to on duplicate key update', () => {
    const r = toMysql(
      `insert into game_settings ("key", value) values ($1, $2::jsonb) on conflict ("key") do update set value = excluded.value, updated_by = excluded."updated_by"`,
      ['k', '1'],
    );
    expect(r.sql).toBe(`insert into game_settings ("key", value) values (?, ?) on duplicate key update value = values(value), updated_by = values("updated_by")`);
    expect(toMysql('insert into p (a) values ($1)\n on conflict (a) do update\n   set a = excluded.a', [1]).sql).toBe(
      'insert into p (a) values (?)\n on duplicate key update a = values(a)',
    );
  });

  it('rejects a placeholder without a value', () => {
    expect(() => toMysql('select $3', [1])).toThrow(/\$3/);
  });
});
