-- Board themes bought with diamonds: what each player owns and which one they show. A room
-- plays on its host's board.

alter table profiles add column board text;

create table user_boards (
  user_id     uuid not null references users (id) on delete cascade,
  board_id    text not null check (char_length(board_id) between 1 and 40),
  gem_tx_id   uuid references gem_transactions (id) on delete set null,
  created_at  timestamptz not null default now(),
  primary key (user_id, board_id)
);

alter table user_boards enable row level security;
