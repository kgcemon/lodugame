-- Ludo 3D platform schema for MySQL 8.0.16+ (same tables as migrations/001_platform.sql).
-- Every coin movement goes through wallet_transactions; balances can never go negative.
-- Times are UTC DATETIME(6); ids are UUID strings; the default collation is case-insensitive,
-- so the plain unique keys on email and username are case-insensitive like the PostgreSQL ones.
-- MySQL forbids CHECK constraints on columns with ON DELETE CASCADE, so the two
-- "not yourself" / ordered-pair checks on friend tables are enforced by the server instead.
-- Statements must end with ";" at the end of a line (the migration runner splits on that).

create table users (
  id                char(36) not null default (uuid()) primary key,
  email             varchar(254) not null,
  password_hash     varchar(255) not null,
  email_verified_at datetime(6),
  role              varchar(10) not null default 'player',
  suspended_at      datetime(6),
  suspended_reason  varchar(300),
  created_at        datetime(6) not null default current_timestamp(6),
  updated_at        datetime(6) not null default current_timestamp(6),
  unique key users_email_unique (email),
  check (char_length(email) between 3 and 254),
  check (role in ('player', 'admin'))
) engine = InnoDB default charset = utf8mb4 collate = utf8mb4_0900_ai_ci;

create table profiles (
  user_id     char(36) not null primary key,
  full_name   varchar(60) not null,
  username    varchar(20) not null,
  public_id   varchar(8) not null,
  avatar_hue  int not null default 210,
  created_at  datetime(6) not null default current_timestamp(6),
  updated_at  datetime(6) not null default current_timestamp(6),
  unique key profiles_username_unique (username),
  unique key profiles_public_id_unique (public_id),
  foreign key (user_id) references users (id) on delete cascade,
  check (char_length(full_name) between 2 and 60),
  check (regexp_like(username, '^[A-Za-z0-9_]{3,20}$', 'c')),
  check (regexp_like(public_id, '^[A-Z0-9]{8}$', 'c')),
  check (avatar_hue between 0 and 359)
) engine = InnoDB default charset = utf8mb4 collate = utf8mb4_0900_ai_ci;

create table sessions (
  id           char(36) not null default (uuid()) primary key,
  user_id      char(36) not null,
  token_hash   varchar(64) not null,
  created_at   datetime(6) not null default current_timestamp(6),
  last_seen_at datetime(6) not null default current_timestamp(6),
  expires_at   datetime(6) not null,
  revoked_at   datetime(6),
  ip           varchar(64),
  user_agent   varchar(300),
  unique key sessions_token_unique (token_hash),
  key sessions_user (user_id),
  foreign key (user_id) references users (id) on delete cascade
) engine = InnoDB default charset = utf8mb4 collate = utf8mb4_0900_ai_ci;

create table auth_tokens (
  id          char(36) not null default (uuid()) primary key,
  user_id     char(36) not null,
  kind        varchar(20) not null,
  token_hash  varchar(64) not null,
  expires_at  datetime(6) not null,
  used_at     datetime(6),
  created_at  datetime(6) not null default current_timestamp(6),
  unique key auth_tokens_token_unique (token_hash),
  key auth_tokens_user (user_id, kind),
  foreign key (user_id) references users (id) on delete cascade,
  check (kind in ('verify_email', 'reset_password'))
) engine = InnoDB default charset = utf8mb4 collate = utf8mb4_0900_ai_ci;

create table wallets (
  user_id       char(36) not null primary key,
  balance       bigint not null default 0,
  total_earned  bigint not null default 0,
  total_spent   bigint not null default 0,
  updated_at    datetime(6) not null default current_timestamp(6),
  foreign key (user_id) references users (id) on delete cascade,
  check (balance >= 0),
  check (total_earned >= 0),
  check (total_spent >= 0)
) engine = InnoDB default charset = utf8mb4 collate = utf8mb4_0900_ai_ci;

create table matches (
  id                char(36) not null default (uuid()) primary key,
  room_code         varchar(20) not null,
  mode              varchar(40) not null,
  entry_fee         int not null,
  payout_multiplier decimal(6, 2) not null,
  max_players       int not null,
  is_private        boolean not null,
  status            varchar(20) not null default 'waiting',
  created_by        char(36),
  winner_user_id    char(36),
  winner_color      varchar(20),
  end_reason        varchar(200),
  created_at        datetime(6) not null default current_timestamp(6),
  started_at        datetime(6),
  ended_at          datetime(6),
  key matches_status (status),
  key matches_created (created_at desc),
  foreign key (created_by) references users (id) on delete set null,
  foreign key (winner_user_id) references users (id) on delete set null,
  check (entry_fee >= 0),
  check (payout_multiplier >= 0),
  check (max_players between 2 and 4),
  check (status in ('waiting', 'in_progress', 'completed', 'cancelled', 'abandoned'))
) engine = InnoDB default charset = utf8mb4 collate = utf8mb4_0900_ai_ci;

create table match_players (
  id          char(36) not null default (uuid()) primary key,
  match_id    char(36) not null,
  user_id     char(36) not null,
  color       varchar(20),
  status      varchar(20) not null default 'joined',
  attempt     int not null default 1,
  "rank"      int,
  entry_paid  bigint not null default 0,
  payout      bigint not null default 0,
  refunded    bigint not null default 0,
  joined_at   datetime(6) not null default current_timestamp(6),
  left_at     datetime(6),
  unique key match_players_match_user (match_id, user_id),
  key match_players_user (user_id, joined_at desc),
  foreign key (match_id) references matches (id) on delete cascade,
  foreign key (user_id) references users (id) on delete cascade,
  check (status in ('joined', 'left', 'playing', 'forfeited', 'finished', 'refunded')),
  check (attempt >= 1),
  check (entry_paid >= 0),
  check (payout >= 0),
  check (refunded >= 0)
) engine = InnoDB default charset = utf8mb4 collate = utf8mb4_0900_ai_ci;

create table wallet_transactions (
  id               char(36) not null default (uuid()) primary key,
  user_id          char(36) not null,
  type             varchar(30) not null,
  amount           bigint not null,
  balance_before   bigint not null,
  balance_after    bigint not null,
  match_id         char(36),
  idempotency_key  varchar(200) not null,
  note             varchar(500),
  created_by       char(36),
  created_at       datetime(6) not null default current_timestamp(6),
  unique key wallet_tx_user_key (user_id, idempotency_key),
  key wallet_tx_user (user_id, created_at desc),
  key wallet_tx_match (match_id),
  key wallet_tx_type (type, created_at desc),
  foreign key (user_id) references users (id) on delete cascade,
  foreign key (match_id) references matches (id) on delete set null,
  foreign key (created_by) references users (id) on delete set null,
  check (type in ('welcome_bonus', 'match_entry', 'match_reward', 'match_refund',
                  'daily_bonus', 'referral_reward', 'admin_adjustment')),
  check (amount <> 0),
  check (balance_before >= 0),
  check (balance_after >= 0),
  check (char_length(idempotency_key) between 1 and 200),
  check (balance_after = balance_before + amount)
) engine = InnoDB default charset = utf8mb4 collate = utf8mb4_0900_ai_ci;

-- One row per match, ever: the primary key makes settlement exactly-once.
create table match_settlements (
  match_id        char(36) not null primary key,
  winner_user_id  char(36),
  total_pot       bigint not null,
  payout          bigint not null,
  house_amount    bigint not null,
  policy          varchar(500) not null,
  settled_at      datetime(6) not null default current_timestamp(6),
  foreign key (match_id) references matches (id) on delete cascade,
  foreign key (winner_user_id) references users (id) on delete set null,
  check (total_pot >= 0),
  check (payout >= 0)
) engine = InnoDB default charset = utf8mb4 collate = utf8mb4_0900_ai_ci;

create table match_disputes (
  id           char(36) not null default (uuid()) primary key,
  match_id     char(36) not null,
  user_id      char(36) not null,
  reason       varchar(500) not null,
  status       varchar(20) not null default 'open',
  admin_note   varchar(500),
  resolved_by  char(36),
  created_at   datetime(6) not null default current_timestamp(6),
  resolved_at  datetime(6),
  unique key match_disputes_match_user (match_id, user_id),
  key match_disputes_status (status, created_at desc),
  foreign key (match_id) references matches (id) on delete cascade,
  foreign key (user_id) references users (id) on delete cascade,
  foreign key (resolved_by) references users (id) on delete set null,
  check (char_length(reason) between 5 and 500),
  check (status in ('open', 'resolved', 'rejected'))
) engine = InnoDB default charset = utf8mb4 collate = utf8mb4_0900_ai_ci;

-- At most one open request between two people, in either direction: pending_pair is only
-- set while the request is pending, and NULLs never collide in a unique key. It is VIRTUAL
-- because MySQL rejects ON DELETE CASCADE foreign keys on the base columns of a STORED one,
-- and the CHAR(36) ids go through RTRIM() because MariaDB 10.5+ refuses generated columns whose
-- value would depend on how CHAR padding is read (sql_mode PAD_CHAR_TO_FULL_LENGTH).
create table friend_requests (
  id            char(36) not null default (uuid()) primary key,
  from_user     char(36) not null,
  to_user       char(36) not null,
  status        varchar(20) not null default 'pending',
  created_at    datetime(6) not null default current_timestamp(6),
  responded_at  datetime(6),
  pending_pair  varchar(73) as (case when status = 'pending' then concat(least(rtrim(from_user), rtrim(to_user)), ':', greatest(rtrim(from_user), rtrim(to_user))) end) virtual,
  unique key friend_requests_one_pending (pending_pair),
  key friend_requests_to (to_user, status),
  key friend_requests_from (from_user, status),
  foreign key (from_user) references users (id) on delete cascade,
  foreign key (to_user) references users (id) on delete cascade,
  check (status in ('pending', 'accepted', 'rejected', 'cancelled'))
) engine = InnoDB default charset = utf8mb4 collate = utf8mb4_0900_ai_ci;

create table friendships (
  user_a      char(36) not null,
  user_b      char(36) not null,
  created_at  datetime(6) not null default current_timestamp(6),
  primary key (user_a, user_b),
  key friendships_b (user_b),
  foreign key (user_a) references users (id) on delete cascade,
  foreign key (user_b) references users (id) on delete cascade
) engine = InnoDB default charset = utf8mb4 collate = utf8mb4_0900_ai_ci;

create table notifications (
  id          char(36) not null default (uuid()) primary key,
  user_id     char(36) not null,
  type        varchar(30) not null,
  payload     json not null default (json_object()),
  read_at     datetime(6),
  created_at  datetime(6) not null default current_timestamp(6),
  key notifications_user (user_id, created_at desc),
  foreign key (user_id) references users (id) on delete cascade,
  check (type in ('friend_request', 'friend_accepted', 'room_invite', 'match_result', 'wallet', 'system'))
) engine = InnoDB default charset = utf8mb4 collate = utf8mb4_0900_ai_ci;

create table game_settings (
  "key"       varchar(100) not null primary key,
  value       json not null,
  updated_at  datetime(6) not null default current_timestamp(6),
  updated_by  char(36),
  foreign key (updated_by) references users (id) on delete set null
) engine = InnoDB default charset = utf8mb4 collate = utf8mb4_0900_ai_ci;

create table audit_logs (
  id               char(36) not null default (uuid()) primary key,
  actor_id         char(36),
  action           varchar(100) not null,
  target_user_id   char(36),
  target_match_id  char(36),
  details          json not null default (json_object()),
  created_at       datetime(6) not null default current_timestamp(6),
  key audit_logs_created (created_at desc),
  foreign key (actor_id) references users (id) on delete set null,
  foreign key (target_user_id) references users (id) on delete set null,
  foreign key (target_match_id) references matches (id) on delete set null
) engine = InnoDB default charset = utf8mb4 collate = utf8mb4_0900_ai_ci;

-- Default configuration (administrators can change it later).
insert ignore into game_settings ("key", value) values
  ('welcome_bonus', '1000'),
  ('daily_bonus', '100'),
  ('payout_multiplier', '2'),
  ('matchmaking_wait_seconds', '45'),
  ('suspicious_amount', '1000'),
  ('match_modes', '[
    {"id":"quick","name":"Quick Match","description":"Find opponents and start playing quickly.","entryFee":10,"maxPlayers":2,"matchType":"1 vs 1","enabled":true},
    {"id":"classic","name":"Classic Match","description":"Enjoy a traditional multiplayer Ludo match.","entryFee":50,"maxPlayers":4,"matchType":"Up to 4 players","enabled":true},
    {"id":"pro","name":"Pro Match","description":"Compete against experienced players.","entryFee":100,"maxPlayers":4,"matchType":"Up to 4 players","enabled":true},
    {"id":"master","name":"Master Match","description":"Challenge players in a high-entry-fee match.","entryFee":250,"maxPlayers":2,"matchType":"1 vs 1","enabled":true},
    {"id":"friendly","name":"Friendly Room","description":"Free private room for friends. Bots allowed, no coins at stake.","entryFee":0,"maxPlayers":4,"matchType":"Private, 2 to 4 players","enabled":true,"privateOnly":true}
  ]');
