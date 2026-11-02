-- Profile pictures (from Google) and profile frames bought with diamonds: the picture and
-- the frame each player shows, and the frames they own.

alter table profiles add column avatar_url text;
alter table profiles add column frame text;

create table user_frames (
  user_id     uuid not null references users (id) on delete cascade,
  frame_id    text not null check (char_length(frame_id) between 1 and 40),
  gem_tx_id   uuid references gem_transactions (id) on delete set null,
  created_at  timestamptz not null default now(),
  primary key (user_id, frame_id)
);

alter table user_frames enable row level security;
