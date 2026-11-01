-- Gems: a premium currency bought through Google Play and spent on dice re-rolls (MySQL/MariaDB).
-- Statements must end with ";" at the end of a line.

alter table wallets add column gems bigint not null default 0, add constraint wallets_gems_nonneg check (gems >= 0);

create table gem_transactions (
  id               char(36) not null default (uuid()) primary key,
  user_id          char(36) not null,
  type             varchar(30) not null,
  amount           bigint not null,
  balance_before   bigint not null,
  balance_after    bigint not null,
  match_id         char(36),
  idempotency_key  varchar(200) not null,
  product_id       varchar(100),
  purchase_token   varchar(512),
  note             varchar(500),
  created_by       char(36),
  created_at       datetime(6) not null default current_timestamp(6),
  unique key gem_tx_user_key (user_id, idempotency_key),
  unique key gem_tx_purchase_token (purchase_token),
  key gem_tx_user (user_id, created_at desc),
  key gem_tx_match (match_id),
  foreign key (user_id) references users (id) on delete cascade,
  foreign key (match_id) references matches (id) on delete set null,
  foreign key (created_by) references users (id) on delete set null,
  check (type in ('purchase', 'reroll', 'reroll_refund', 'admin_adjustment')),
  check (amount <> 0),
  check (balance_before >= 0),
  check (balance_after >= 0),
  check (char_length(idempotency_key) between 1 and 200),
  check (balance_after = balance_before + amount)
) engine = InnoDB default charset = utf8mb4 collate = utf8mb4_0900_ai_ci;
