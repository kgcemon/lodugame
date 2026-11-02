-- Guest accounts (play without signing in; can be linked to Google later) and each
-- player's country, looked up from their IP address (null when unknown) (MySQL/MariaDB).
-- Statements must end with ";" at the end of a line.

alter table users add column is_guest boolean not null default false;
alter table profiles add column country varchar(2) null;
