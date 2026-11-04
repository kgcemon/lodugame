-- Blocking and reporting players. player_blocks is one row per block, one way: the blocker stops
-- hearing the blocked player. player_reports is one row per report for an administrator to decide;
-- chat_excerpt is the server's own copy of what the reported player said in the room (chat is
-- otherwise only kept in memory).

create table player_blocks (
  blocker_id uuid not null references users (id) on delete cascade,
  blocked_id uuid not null references users (id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (blocker_id, blocked_id),
  check (blocker_id <> blocked_id)
);
create index player_blocks_blocked on player_blocks (blocked_id);

create table player_reports (
  id           uuid primary key,
  reporter_id  uuid references users (id) on delete set null,
  target_id    uuid not null references users (id) on delete cascade,
  reason       text not null check (reason in ('harassment', 'hate', 'sexual', 'spam', 'cheating', 'other')),
  note         text check (char_length(note) <= 500),
  match_id     uuid references matches (id) on delete set null,
  chat_excerpt jsonb,
  status       text not null default 'open' check (status in ('open', 'resolved', 'dismissed')),
  admin_note   text,
  resolved_by  uuid references users (id) on delete set null,
  created_at   timestamptz not null default now(),
  resolved_at  timestamptz
);
create index player_reports_status on player_reports (status, created_at desc);
create index player_reports_target on player_reports (target_id);
create index player_reports_reporter on player_reports (reporter_id, target_id);

alter table player_blocks enable row level security;
alter table player_reports enable row level security;
