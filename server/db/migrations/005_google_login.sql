-- Google Sign-In: the Google account (its stable "sub" id) linked to a player. Accounts made
-- with Google have no password (password_hash 'google', which matches nothing).

alter table users add column google_sub text;
create unique index users_google_sub_unique on users (google_sub);
