-- Blocking and reporting players (MySQL/MariaDB). player_blocks is one row per block, one way: the
-- blocker stops hearing the blocked player. player_reports is one row per report for an administrator
-- to decide; chat_excerpt is the server's own copy of what the reported player said in the room (chat
-- is otherwise only kept in memory). MySQL refuses CHECK constraints on columns with a cascading
-- foreign key, so "not yourself" is the API's job here.
-- Statements must end with ";" at the end of a line.

create table player_blocks (
  blocker_id char(36) not null,
  blocked_id char(36) not null,
  created_at datetime(6) not null default current_timestamp(6),
  primary key (blocker_id, blocked_id),
  key player_blocks_blocked (blocked_id),
  foreign key (blocker_id) references users (id) on delete cascade,
  foreign key (blocked_id) references users (id) on delete cascade
) engine = InnoDB default charset = utf8mb4 collate = utf8mb4_0900_ai_ci;

create table player_reports (
  id           char(36) not null primary key,
  reporter_id  char(36),
  target_id    char(36) not null,
  reason       varchar(20) not null,
  note         varchar(500),
  match_id     char(36),
  chat_excerpt json,
  status       varchar(20) not null default 'open',
  admin_note   varchar(500),
  resolved_by  char(36),
  created_at   datetime(6) not null default current_timestamp(6),
  resolved_at  datetime(6),
  key player_reports_status (status, created_at desc),
  key player_reports_target (target_id),
  key player_reports_reporter (reporter_id, target_id),
  foreign key (reporter_id) references users (id) on delete set null,
  foreign key (target_id) references users (id) on delete cascade,
  foreign key (match_id) references matches (id) on delete set null,
  foreign key (resolved_by) references users (id) on delete set null,
  check (reason in ('harassment', 'hate', 'sexual', 'spam', 'cheating', 'other')),
  check (status in ('open', 'resolved', 'dismissed'))
) engine = InnoDB default charset = utf8mb4 collate = utf8mb4_0900_ai_ci;
