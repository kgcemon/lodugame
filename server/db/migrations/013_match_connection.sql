-- Connection tracking and forfeits: every player's connection state, reconnection deadline and
-- forfeit stay on the match record, stamped by the server. `lineup` keeps who sat down (bots too).

alter table matches add column lineup text;

alter table match_players
  add column connection_status text not null default 'connected'
    check (connection_status in ('connected', 'disconnected', 'reconnected', 'forfeited', 'eliminated', 'winner')),
  add column disconnected_at timestamptz,
  add column reconnect_deadline timestamptz,
  add column reconnected_at timestamptz,
  add column forfeited_at timestamptz,
  add column forfeit_reason text check (forfeit_reason in ('timeout', 'left', 'removed')),
  add column result_seen_at timestamptz;
