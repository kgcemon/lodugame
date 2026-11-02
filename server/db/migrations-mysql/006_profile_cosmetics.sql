-- Profile pictures (from Google) and profile frames bought with diamonds (MySQL/MariaDB):
-- the picture and the frame each player shows, and the frames they own.
-- Statements must end with ";" at the end of a line.

alter table profiles add column avatar_url varchar(512) null;
alter table profiles add column frame varchar(40) null;

create table user_frames (
  user_id     char(36) not null,
  frame_id    varchar(40) not null,
  gem_tx_id   char(36),
  created_at  datetime(6) not null default current_timestamp(6),
  primary key (user_id, frame_id),
  foreign key (user_id) references users (id) on delete cascade,
  foreign key (gem_tx_id) references gem_transactions (id) on delete set null,
  check (char_length(frame_id) between 1 and 40)
) engine = InnoDB default charset = utf8mb4 collate = utf8mb4_0900_ai_ci;
