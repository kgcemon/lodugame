-- Gems: a premium currency bought through Google Play and spent on dice re-rolls.
-- Like coins, every change is a ledger row and the balance can never go negative.

alter table wallets add column gems bigint not null default 0 check (gems >= 0);

create table gem_transactions (
  id               uuid primary key default gen_random_uuid(),
  user_id          uuid not null references users (id) on delete cascade,
  type             text not null check (type in ('purchase', 'reroll', 'reroll_refund', 'admin_adjustment')),
  amount           bigint not null check (amount <> 0),
  balance_before   bigint not null check (balance_before >= 0),
  balance_after    bigint not null check (balance_after >= 0),
  match_id         uuid references matches (id) on delete set null,
  idempotency_key  text not null check (char_length(idempotency_key) between 1 and 200),
  product_id       text,
  -- A Google Play purchase token can be redeemed exactly once, by one account.
  purchase_token   text unique,
  note             text,
  created_by       uuid references users (id) on delete set null,
  created_at       timestamptz not null default now(),
  check (balance_after = balance_before + amount),
  unique (user_id, idempotency_key)
);
create index gem_tx_user on gem_transactions (user_id, created_at desc);
create index gem_tx_match on gem_transactions (match_id);

alter table gem_transactions enable row level security;
