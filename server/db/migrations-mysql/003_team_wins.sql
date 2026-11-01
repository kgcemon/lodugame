-- 2 vs 2: both partners win a team match, so "did this player win" moves from
-- matches.winner_user_id (one user) to a per-player flag (MySQL/MariaDB).
-- Statements must end with ";" at the end of a line.

alter table match_players add column won boolean not null default false;

update match_players mp join matches m on m.id = mp.match_id set mp.won = true where m.winner_user_id = mp.user_id;
