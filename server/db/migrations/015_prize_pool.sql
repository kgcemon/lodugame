-- The official prize pool, fixed by the server when a match is confirmed: what everyone paid in.

alter table matches add column prize_pool bigint check (prize_pool >= 0);
