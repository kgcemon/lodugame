-- Gifts from administrators: one row per recipient, credited once when the player claims it.
-- gift_claims' primary key is the "claimed exactly once" constraint; the coin and diamond
-- ledgers add a second one through the idempotency key gift:<id>.

alter table wallet_transactions drop constraint wallet_transactions_type_check;
alter table wallet_transactions add constraint wallet_transactions_type_check
  check (type in ('welcome_bonus', 'match_entry', 'match_reward', 'match_refund',
                  'daily_bonus', 'referral_reward', 'admin_adjustment', 'shop_purchase', 'gift'));

alter table gem_transactions drop constraint gem_transactions_type_check;
alter table gem_transactions add constraint gem_transactions_type_check
  check (type in ('purchase', 'reroll', 'reroll_refund', 'admin_adjustment', 'skin_purchase', 'daily_spin', 'gift'));

create table gifts (
  id            uuid primary key,
  -- One send action: a retried request cannot give the same player the same gift twice.
  batch_id      uuid not null,
  sender_id     uuid references users (id) on delete set null,
  recipient_id  uuid not null references users (id) on delete cascade,
  kind          text not null check (kind in ('coins', 'diamonds', 'dice', 'board', 'frame')),
  item_id       text,
  item_name     text not null,
  quantity      bigint not null check (quantity > 0),
  rarity        text not null,
  -- The presentation only (Royal, Mystery, ...); null is a plain gift. The content above is what is credited.
  box_style     text,
  message       text not null default '' check (char_length(message) <= 200),
  status        text not null default 'pending' check (status in ('pending', 'opened', 'claimed', 'expired', 'cancelled')),
  expires_at    timestamptz,
  created_at    timestamptz not null default now(),
  opened_at     timestamptz,
  claimed_at    timestamptz,
  cancelled_at  timestamptz,
  unique (batch_id, recipient_id)
);
create index gifts_inbox on gifts (recipient_id, created_at desc);
create index gifts_status on gifts (status, created_at desc);
create index gifts_expiry on gifts (expires_at);

-- What the player really received: the content, or (a cosmetic they already own) its refund.
create table gift_claims (
  gift_id        uuid primary key references gifts (id) on delete cascade,
  user_id        uuid not null references users (id) on delete cascade,
  granted_kind   text not null,
  granted_item   text,
  granted_amount bigint not null,
  duplicate      boolean not null default false,
  tx_id          uuid,
  claimed_at     timestamptz not null default now()
);

alter table gifts enable row level security;
alter table gift_claims enable row level security;
