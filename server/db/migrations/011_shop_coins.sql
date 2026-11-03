-- Boards are bought with coins: a new coin ledger entry type for shop purchases.

alter table wallet_transactions drop constraint wallet_transactions_type_check;
alter table wallet_transactions add constraint wallet_transactions_type_check
  check (type in ('welcome_bonus', 'match_entry', 'match_reward', 'match_refund',
                  'daily_bonus', 'referral_reward', 'admin_adjustment', 'shop_purchase'));
