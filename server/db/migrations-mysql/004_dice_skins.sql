-- Dice skins bought with diamonds (MySQL/MariaDB): what each player owns, which one they
-- show, and a new diamond ledger entry type for the purchase.
-- Statements must end with ";" at the end of a line.

alter table profiles add column dice_skin varchar(40) not null default 'classic';

create table user_dice_skins (
  user_id     char(36) not null,
  skin_id     varchar(40) not null,
  gem_tx_id   char(36),
  created_at  datetime(6) not null default current_timestamp(6),
  primary key (user_id, skin_id),
  foreign key (user_id) references users (id) on delete cascade,
  foreign key (gem_tx_id) references gem_transactions (id) on delete set null,
  check (char_length(skin_id) between 1 and 40)
) engine = InnoDB default charset = utf8mb4 collate = utf8mb4_0900_ai_ci;

-- The type check was created without a name (MySQL calls it gem_transactions_chk_1,
-- MariaDB CONSTRAINT_1, ...): find it by its clause and replace it.
set @old_check = (
  select tc.constraint_name
  from information_schema.table_constraints tc
  join information_schema.check_constraints cc
    on cc.constraint_schema = tc.constraint_schema and cc.constraint_name = tc.constraint_name
  where tc.table_schema = database() and tc.table_name = 'gem_transactions' and tc.constraint_type = 'CHECK'
    and cc.check_clause like '%reroll_refund%'
  limit 1
);
set @drop_check = if(@old_check is null, 'select 1', concat('alter table gem_transactions drop constraint "', @old_check, '"'));
prepare drop_check from @drop_check;
execute drop_check;
deallocate prepare drop_check;

alter table gem_transactions add constraint gem_tx_type
  check (type in ('purchase', 'reroll', 'reroll_refund', 'admin_adjustment', 'skin_purchase'));
