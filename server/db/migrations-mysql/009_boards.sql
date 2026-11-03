-- Board themes bought with diamonds (MySQL/MariaDB): what each player owns and which one
-- they show. A room plays on its host's board.
-- Statements must end with ";" at the end of a line.

alter table profiles add column board varchar(40) null;

create table user_boards (
  user_id     char(36) not null,
  board_id    varchar(40) not null,
  gem_tx_id   char(36),
  created_at  datetime(6) not null default current_timestamp(6),
  primary key (user_id, board_id),
  foreign key (user_id) references users (id) on delete cascade,
  foreign key (gem_tx_id) references gem_transactions (id) on delete set null,
  check (char_length(board_id) between 1 and 40)
) engine = InnoDB default charset = utf8mb4 collate = utf8mb4_0900_ai_ci;
