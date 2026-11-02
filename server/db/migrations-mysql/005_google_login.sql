-- Google Sign-In (MySQL/MariaDB): the Google account (its stable "sub" id) linked to a player.
-- Accounts made with Google have no password (password_hash 'google', which matches nothing).
-- Statements must end with ";" at the end of a line.

alter table users add column google_sub varchar(64) null;
alter table users add unique key users_google_sub_unique (google_sub);
