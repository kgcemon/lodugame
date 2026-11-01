-- 2 vs 2: both partners win a team match, so "did this player win" moves from
-- matches.winner_user_id (one user) to a per-player flag.

alter table match_players add column won boolean not null default false;

update match_players set won = true
from matches m
where m.id = match_players.match_id and m.winner_user_id = match_players.user_id;
