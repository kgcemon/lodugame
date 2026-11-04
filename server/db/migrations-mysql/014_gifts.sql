-- Gifts from administrators (MySQL/MariaDB): one row per recipient, credited once when the
-- player claims it. gift_claims' primary key is the "claimed exactly once" constraint; the coin
-- and diamond ledgers add a second one through the idempotency key gift:<id>.
-- MySQL forbids CHECK constraints on columns with ON DELETE CASCADE / SET NULL actions, and these
-- checks don't touch them. Statements must end with ";" at the end of a line.

-- 012 and 007 gave both type checks these names.
alter table wallet_transactions drop constraint wallet_tx_type;
alter table wallet_transactions add constraint wallet_tx_type
  check (type in ('welcome_bonus', 'match_entry', 'match_reward', 'match_refund',
                  'daily_bonus', 'referral_reward', 'admin_adjustment', 'shop_purchase', 'gift'));

alter table gem_transactions drop constraint gem_tx_type;
alter table gem_transactions add constraint gem_tx_type
  check (type in ('purchase', 'reroll', 'reroll_refund', 'admin_adjustment', 'skin_purchase', 'daily_spin', 'gift'));

create table gifts (
  id            char(36) not null primary key,
  batch_id      char(36) not null,
  sender_id     char(36),
  recipient_id  char(36) not null,
  kind          varchar(20) not null,
  item_id       varchar(40),
  item_name     varchar(60) not null,
  quantity      bigint not null,
  rarity        varchar(20) not null,
  box_style     varchar(20),
  message       varchar(200) not null default '',
  status        varchar(20) not null default 'pending',
  expires_at    datetime(6),
  created_at    datetime(6) not null default current_timestamp(6),
  opened_at     datetime(6),
  claimed_at    datetime(6),
  cancelled_at  datetime(6),
  unique key gifts_batch_recipient (batch_id, recipient_id),
  key gifts_inbox (recipient_id, created_at desc),
  key gifts_status (status, created_at desc),
  key gifts_expiry (expires_at),
  foreign key (sender_id) references users (id) on delete set null,
  foreign key (recipient_id) references users (id) on delete cascade,
  check (kind in ('coins', 'diamonds', 'dice', 'board', 'frame')),
  check (quantity > 0),
  check (status in ('pending', 'opened', 'claimed', 'expired', 'cancelled'))
) engine = InnoDB default charset = utf8mb4 collate = utf8mb4_0900_ai_ci;

create table gift_claims (
  gift_id        char(36) not null primary key,
  user_id        char(36) not null,
  granted_kind   varchar(20) not null,
  granted_item   varchar(40),
  granted_amount bigint not null,
  duplicate      boolean not null default false,
  tx_id          char(36),
  claimed_at     datetime(6) not null default current_timestamp(6),
  foreign key (gift_id) references gifts (id) on delete cascade,
  foreign key (user_id) references users (id) on delete cascade
) engine = InnoDB default charset = utf8mb4 collate = utf8mb4_0900_ai_ci;
