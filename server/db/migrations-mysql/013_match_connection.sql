-- Connection tracking and forfeits (MySQL/MariaDB): every player's connection state, reconnection
-- deadline and forfeit stay on the match record, stamped by the server. `lineup` keeps who sat
-- down (bots too). Statements must end with ";" at the end of a line.

alter table matches add column lineup text;

alter table match_players
  add column connection_status varchar(20) not null default 'connected'
    check (connection_status in ('connected', 'disconnected', 'reconnected', 'forfeited', 'eliminated', 'winner')),
  add column disconnected_at datetime(6),
  add column reconnect_deadline datetime(6),
  add column reconnected_at datetime(6),
  add column forfeited_at datetime(6),
  add column forfeit_reason varchar(20) check (forfeit_reason in ('timeout', 'left', 'removed')),
  add column result_seen_at datetime(6);
