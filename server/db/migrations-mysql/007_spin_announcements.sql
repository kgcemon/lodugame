-- Daily spin (a new diamond ledger entry type) and announcements: popups an administrator
-- shows on the home screen, with a title, text and an optional picture (MySQL/MariaDB).
-- Statements must end with ";" at the end of a line.

alter table gem_transactions drop constraint gem_tx_type;
alter table gem_transactions add constraint gem_tx_type
  check (type in ('purchase', 'reroll', 'reroll_refund', 'admin_adjustment', 'skin_purchase', 'daily_spin'));

create table announcements (
  id           char(36) not null default (uuid()) primary key,
  title        varchar(80) not null,
  body         text not null,
  image_url    varchar(1000),
  image_type   varchar(40),
  image_data   mediumtext,
  button_text  varchar(30) not null default 'OK',
  active       boolean not null default true,
  created_by   char(36),
  created_at   datetime(6) not null default current_timestamp(6),
  key announcements_active (active, created_at desc),
  foreign key (created_by) references users (id) on delete set null,
  check (char_length(title) between 1 and 80),
  check (char_length(body) <= 1000)
) engine = InnoDB default charset = utf8mb4 collate = utf8mb4_0900_ai_ci;
