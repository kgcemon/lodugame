-- Dice skins bought with diamonds: what each player owns, which one they show, and a new
-- diamond ledger entry type for the purchase.

alter table profiles add column dice_skin text not null default 'classic';

create table user_dice_skins (
  user_id     uuid not null references users (id) on delete cascade,
  skin_id     text not null check (char_length(skin_id) between 1 and 40),
  gem_tx_id   uuid references gem_transactions (id) on delete set null,
  created_at  timestamptz not null default now(),
  primary key (user_id, skin_id)
);

alter table user_dice_skins enable row level security;

alter table gem_transactions drop constraint gem_transactions_type_check;
alter table gem_transactions add constraint gem_transactions_type_check
  check (type in ('purchase', 'reroll', 'reroll_refund', 'admin_adjustment', 'skin_purchase'));
