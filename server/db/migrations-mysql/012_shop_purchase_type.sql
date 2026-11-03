-- Boards bought with coins (MySQL/MariaDB): the coin ledger's type check does exist here
-- (001 made it, unnamed), so 011 left shop purchases refused. Add 'shop_purchase' to it.
-- Statements must end with ";" at the end of a line.

-- The type check was created without a name (MySQL calls it wallet_transactions_chk_1,
-- MariaDB CONSTRAINT_1, ...): find it by its clause and replace it.
set @old_check = (
  select tc.constraint_name
  from information_schema.table_constraints tc
  join information_schema.check_constraints cc
    on cc.constraint_schema = tc.constraint_schema and cc.constraint_name = tc.constraint_name
  where tc.table_schema = database() and tc.table_name = 'wallet_transactions' and tc.constraint_type = 'CHECK'
    and cc.check_clause like '%referral_reward%'
  limit 1
);
set @drop_check = if(@old_check is null, 'select 1', concat('alter table wallet_transactions drop constraint "', @old_check, '"'));
prepare drop_check from @drop_check;
execute drop_check;
deallocate prepare drop_check;

alter table wallet_transactions add constraint wallet_tx_type
  check (type in ('welcome_bonus', 'match_entry', 'match_reward', 'match_refund',
                  'daily_bonus', 'referral_reward', 'admin_adjustment', 'shop_purchase'));
