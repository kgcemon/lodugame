-- Daily spin (a new diamond ledger entry type) and announcements: popups an administrator
-- shows on the home screen, with a title, text and an optional picture.

alter table gem_transactions drop constraint gem_transactions_type_check;
alter table gem_transactions add constraint gem_transactions_type_check
  check (type in ('purchase', 'reroll', 'reroll_refund', 'admin_adjustment', 'skin_purchase', 'daily_spin'));

create table announcements (
  id           uuid primary key default gen_random_uuid(),
  title        text not null check (char_length(title) between 1 and 80),
  body         text not null default '' check (char_length(body) <= 1000),
  -- A picture: either a link (image_url) or an uploaded file (image_data, base64).
  image_url    text,
  image_type   text,
  image_data   text,
  button_text  text not null default 'OK',
  active       boolean not null default true,
  created_by   uuid references users (id) on delete set null,
  created_at   timestamptz not null default now()
);
create index announcements_active on announcements (active, created_at desc);

alter table announcements enable row level security;
