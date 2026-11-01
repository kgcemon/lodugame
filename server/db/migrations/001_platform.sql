-- Ludo 3D platform schema: accounts, wallets, ledger, matches, social, admin.
-- Every coin movement goes through wallet_transactions; balances can never go negative.

create table users (
  id               uuid primary key default gen_random_uuid(),
  email            text not null check (char_length(email) between 3 and 254),
  password_hash    text not null,
  email_verified_at timestamptz,
  role             text not null default 'player' check (role in ('player', 'admin')),
  suspended_at     timestamptz,
  suspended_reason text,
  created_at       timestamptz not null default now(),
  updated_at       timestamptz not null default now()
);
create unique index users_email_unique on users (lower(email));

create table profiles (
  user_id     uuid primary key references users (id) on delete cascade,
  full_name   text not null check (char_length(full_name) between 2 and 60),
  username    text not null check (username ~ '^[A-Za-z0-9_]{3,20}$'),
  public_id   text not null unique check (public_id ~ '^[A-Z0-9]{8}$'),
  avatar_hue  int  not null default 210 check (avatar_hue between 0 and 359),
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now()
);
create unique index profiles_username_unique on profiles (lower(username));

create table sessions (
  id          uuid primary key default gen_random_uuid(),
  user_id     uuid not null references users (id) on delete cascade,
  token_hash  text not null unique,
  created_at  timestamptz not null default now(),
  last_seen_at timestamptz not null default now(),
  expires_at  timestamptz not null,
  revoked_at  timestamptz,
  ip          text,
  user_agent  text
);
create index sessions_user on sessions (user_id);

create table auth_tokens (
  id          uuid primary key default gen_random_uuid(),
  user_id     uuid not null references users (id) on delete cascade,
  kind        text not null check (kind in ('verify_email', 'reset_password')),
  token_hash  text not null unique,
  expires_at  timestamptz not null,
  used_at     timestamptz,
  created_at  timestamptz not null default now()
);
create index auth_tokens_user on auth_tokens (user_id, kind);

create table wallets (
  user_id       uuid primary key references users (id) on delete cascade,
  balance       bigint not null default 0 check (balance >= 0),
  total_earned  bigint not null default 0 check (total_earned >= 0),
  total_spent   bigint not null default 0 check (total_spent >= 0),
  updated_at    timestamptz not null default now()
);

create table matches (
  id                uuid primary key default gen_random_uuid(),
  room_code         text not null,
  mode              text not null,
  entry_fee         int  not null check (entry_fee >= 0),
  payout_multiplier numeric(6, 2) not null check (payout_multiplier >= 0),
  max_players       int  not null check (max_players between 2 and 4),
  is_private        boolean not null,
  status            text not null default 'waiting'
                    check (status in ('waiting', 'in_progress', 'completed', 'cancelled', 'abandoned')),
  created_by        uuid references users (id) on delete set null,
  winner_user_id    uuid references users (id) on delete set null,
  winner_color      text,
  end_reason        text,
  created_at        timestamptz not null default now(),
  started_at        timestamptz,
  ended_at          timestamptz
);
create index matches_status on matches (status);
create index matches_created on matches (created_at desc);

create table match_players (
  id          uuid primary key default gen_random_uuid(),
  match_id    uuid not null references matches (id) on delete cascade,
  user_id     uuid not null references users (id) on delete cascade,
  color       text,
  status      text not null default 'joined'
              check (status in ('joined', 'left', 'playing', 'forfeited', 'finished', 'refunded')),
  attempt     int  not null default 1 check (attempt >= 1),
  rank        int,
  entry_paid  bigint not null default 0 check (entry_paid >= 0),
  payout      bigint not null default 0 check (payout >= 0),
  refunded    bigint not null default 0 check (refunded >= 0),
  joined_at   timestamptz not null default now(),
  left_at     timestamptz,
  unique (match_id, user_id)
);
create index match_players_user on match_players (user_id, joined_at desc);

create table wallet_transactions (
  id               uuid primary key default gen_random_uuid(),
  user_id          uuid not null references users (id) on delete cascade,
  type             text not null check (type in (
                     'welcome_bonus', 'match_entry', 'match_reward', 'match_refund',
                     'daily_bonus', 'referral_reward', 'admin_adjustment')),
  amount           bigint not null check (amount <> 0),
  balance_before   bigint not null check (balance_before >= 0),
  balance_after    bigint not null check (balance_after >= 0),
  match_id         uuid references matches (id) on delete set null,
  idempotency_key  text not null check (char_length(idempotency_key) between 1 and 200),
  note             text,
  created_by       uuid references users (id) on delete set null,
  created_at       timestamptz not null default now(),
  check (balance_after = balance_before + amount),
  unique (user_id, idempotency_key)
);
create index wallet_tx_user on wallet_transactions (user_id, created_at desc);
create index wallet_tx_match on wallet_transactions (match_id);
create index wallet_tx_type on wallet_transactions (type, created_at desc);

-- One row per match, ever: the primary key makes settlement exactly-once.
create table match_settlements (
  match_id        uuid primary key references matches (id) on delete cascade,
  winner_user_id  uuid references users (id) on delete set null,
  total_pot       bigint not null check (total_pot >= 0),
  payout          bigint not null check (payout >= 0),
  house_amount    bigint not null,
  policy          text not null,
  settled_at      timestamptz not null default now()
);

create table match_disputes (
  id           uuid primary key default gen_random_uuid(),
  match_id     uuid not null references matches (id) on delete cascade,
  user_id      uuid not null references users (id) on delete cascade,
  reason       text not null check (char_length(reason) between 5 and 500),
  status       text not null default 'open' check (status in ('open', 'resolved', 'rejected')),
  admin_note   text,
  resolved_by  uuid references users (id) on delete set null,
  created_at   timestamptz not null default now(),
  resolved_at  timestamptz,
  unique (match_id, user_id)
);
create index match_disputes_status on match_disputes (status, created_at desc);

create table friend_requests (
  id            uuid primary key default gen_random_uuid(),
  from_user     uuid not null references users (id) on delete cascade,
  to_user       uuid not null references users (id) on delete cascade,
  status        text not null default 'pending' check (status in ('pending', 'accepted', 'rejected', 'cancelled')),
  created_at    timestamptz not null default now(),
  responded_at  timestamptz,
  check (from_user <> to_user)
);
-- At most one open request between two people, in either direction.
create unique index friend_requests_one_pending
  on friend_requests (least(from_user, to_user), greatest(from_user, to_user)) where status = 'pending';
create index friend_requests_to on friend_requests (to_user, status);
create index friend_requests_from on friend_requests (from_user, status);

create table friendships (
  user_a      uuid not null references users (id) on delete cascade,
  user_b      uuid not null references users (id) on delete cascade,
  created_at  timestamptz not null default now(),
  primary key (user_a, user_b),
  check (user_a < user_b)
);
create index friendships_b on friendships (user_b);

create table notifications (
  id          uuid primary key default gen_random_uuid(),
  user_id     uuid not null references users (id) on delete cascade,
  type        text not null check (type in ('friend_request', 'friend_accepted', 'room_invite', 'match_result', 'wallet', 'system')),
  payload     jsonb not null default '{}'::jsonb,
  read_at     timestamptz,
  created_at  timestamptz not null default now()
);
create index notifications_user on notifications (user_id, created_at desc);

create table game_settings (
  key         text primary key,
  value       jsonb not null,
  updated_at  timestamptz not null default now(),
  updated_by  uuid references users (id) on delete set null
);

create table audit_logs (
  id               uuid primary key default gen_random_uuid(),
  actor_id         uuid references users (id) on delete set null,
  action           text not null,
  target_user_id   uuid references users (id) on delete set null,
  target_match_id  uuid references matches (id) on delete set null,
  details          jsonb not null default '{}'::jsonb,
  created_at       timestamptz not null default now()
);
create index audit_logs_created on audit_logs (created_at desc);

-- Default configuration (administrators can change it later).
insert into game_settings (key, value) values
  ('welcome_bonus', '1000'::jsonb),
  ('daily_bonus', '100'::jsonb),
  ('payout_multiplier', '2'::jsonb),
  ('matchmaking_wait_seconds', '45'::jsonb),
  ('suspicious_amount', '1000'::jsonb),
  ('match_modes', '[
    {"id":"quick","name":"Quick Match","description":"Find opponents and start playing quickly.","entryFee":10,"maxPlayers":2,"matchType":"1 vs 1","enabled":true},
    {"id":"classic","name":"Classic Match","description":"Enjoy a traditional multiplayer Ludo match.","entryFee":50,"maxPlayers":4,"matchType":"Up to 4 players","enabled":true},
    {"id":"pro","name":"Pro Match","description":"Compete against experienced players.","entryFee":100,"maxPlayers":4,"matchType":"Up to 4 players","enabled":true},
    {"id":"master","name":"Master Match","description":"Challenge players in a high-entry-fee match.","entryFee":250,"maxPlayers":2,"matchType":"1 vs 1","enabled":true},
    {"id":"friendly","name":"Friendly Room","description":"Free private room for friends. Bots allowed, no coins at stake.","entryFee":0,"maxPlayers":4,"matchType":"Private, 2 to 4 players","enabled":true,"privateOnly":true}
  ]'::jsonb)
on conflict (key) do nothing;

-- The browser never talks to the database: all access goes through the game server, which
-- connects as the table owner. Enabling RLS with no policies denies everything to any other
-- role (e.g. Supabase's anon/authenticated keys), so wallets cannot be edited from outside.
alter table users enable row level security;
alter table profiles enable row level security;
alter table sessions enable row level security;
alter table auth_tokens enable row level security;
alter table wallets enable row level security;
alter table wallet_transactions enable row level security;
alter table matches enable row level security;
alter table match_players enable row level security;
alter table match_settlements enable row level security;
alter table match_disputes enable row level security;
alter table friend_requests enable row level security;
alter table friendships enable row level security;
alter table notifications enable row level security;
alter table game_settings enable row level security;
alter table audit_logs enable row level security;
