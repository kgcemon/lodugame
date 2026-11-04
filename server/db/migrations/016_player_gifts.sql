-- Gifts players send each other in a match. gift_products is the catalogue an administrator edits.
-- player_gifts is one row per gift sent: the sale (the ledgers hold the payment, under the key
-- player-gift:<idempotency_key>), its delivery (seen_at) and the recipient's collection (displayed).
-- Name, price and look are copied, so a later catalogue change doesn't rewrite history.

alter table wallet_transactions drop constraint wallet_transactions_type_check;
alter table wallet_transactions add constraint wallet_transactions_type_check
  check (type in ('welcome_bonus', 'match_entry', 'match_reward', 'match_refund',
                  'daily_bonus', 'referral_reward', 'admin_adjustment', 'shop_purchase', 'gift', 'player_gift'));

alter table gem_transactions drop constraint gem_transactions_type_check;
alter table gem_transactions add constraint gem_transactions_type_check
  check (type in ('purchase', 'reroll', 'reroll_refund', 'admin_adjustment', 'skin_purchase', 'daily_spin', 'gift', 'player_gift'));

create table gift_products (
  id              text primary key,
  name            text not null,
  currency        text not null check (currency in ('coins', 'diamonds')),
  price           bigint not null check (price > 0),
  category        text not null,
  rarity          text not null,
  model           text not null,
  send_anim       text not null,
  receive_anim    text not null,
  particle        text not null,
  enabled         boolean not null default true,
  featured        boolean not null default false,
  event_name      text,
  available_until timestamptz,
  sort_order      int not null default 1000,
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now()
);

create table player_gifts (
  id              uuid primary key,
  sender_id       uuid references users (id) on delete set null,
  recipient_id    uuid not null references users (id) on delete cascade,
  product_id      text not null references gift_products (id),
  name            text not null,
  currency        text not null check (currency in ('coins', 'diamonds')),
  price           bigint not null check (price > 0),
  rarity          text not null,
  model           text not null,
  send_anim       text not null,
  receive_anim    text not null,
  particle        text not null,
  match_id        uuid references matches (id) on delete set null,
  idempotency_key text not null,
  created_at      timestamptz not null default now(),
  seen_at         timestamptz,
  displayed       boolean not null default false,
  reaction        text,
  unique (sender_id, idempotency_key)
);
create index player_gifts_recipient on player_gifts (recipient_id, created_at desc);
create index player_gifts_sender on player_gifts (sender_id, created_at desc);
create index player_gifts_product on player_gifts (product_id);

insert into gift_products (id, name, currency, price, category, rarity, model, send_anim, receive_anim, particle, sort_order) values
  ('golden_rose', 'Golden Rose', 'coins', 100, 'romance', 'common', 'rose', 'float', 'pop', 'petals', 10),
  ('chocolate_box', 'Chocolate Box', 'coins', 250, 'sweet', 'common', 'chocolates', 'arc', 'open', 'sparks', 20),
  ('love_letter', 'Love Letter', 'coins', 300, 'romance', 'common', 'letter', 'flutter', 'unfold', 'hearts', 30),
  ('teddy_bear', 'Teddy Bear', 'coins', 500, 'cute', 'rare', 'bear', 'hop', 'bounce', 'hearts', 40),
  ('golden_heart', 'Golden Heart', 'coins', 750, 'romance', 'rare', 'heart_gold', 'arc', 'expand', 'hearts', 50),
  ('flower_bouquet', 'Flower Bouquet', 'coins', 1000, 'romance', 'rare', 'bouquet', 'float', 'pop', 'petals', 60),
  ('celebration_balloon', 'Celebration Balloon', 'coins', 1200, 'celebration', 'rare', 'balloon', 'rise', 'bob', 'confetti', 70),
  ('cute_cat', 'Cute Cat', 'coins', 1500, 'cute', 'rare', 'cat', 'hop', 'bounce', 'stars', 80),
  ('birthday_cake', 'Birthday Cake', 'coins', 2000, 'celebration', 'epic', 'cake', 'arc', 'pop', 'confetti', 90),
  ('golden_trophy', 'Golden Trophy', 'coins', 2500, 'celebration', 'epic', 'trophy', 'swoop', 'hover', 'stars', 100),
  ('diamond_heart', 'Diamond Love Heart', 'diamonds', 20, 'romance', 'rare', 'heart_diamond', 'arc', 'expand', 'hearts', 110),
  ('royal_bouquet', 'Royal Rose Bouquet', 'diamonds', 30, 'romance', 'rare', 'royal_bouquet', 'float', 'pop', 'petals', 120),
  ('magic_butterfly', 'Magic Butterfly', 'diamonds', 40, 'romance', 'rare', 'butterfly', 'flutter', 'bob', 'magic', 130),
  ('pink_balloon', 'Pink Love Balloon', 'diamonds', 50, 'romance', 'rare', 'balloon_heart', 'rise', 'bob', 'hearts', 140),
  ('royal_crown', 'Royal Crown', 'diamonds', 100, 'royal', 'epic', 'crown', 'swoop', 'hover', 'stars', 150),
  ('diamond_teddy', 'Diamond Teddy Bear', 'diamonds', 120, 'cute', 'epic', 'bear_diamond', 'hop', 'bounce', 'diamonds', 160),
  ('royal_peacock', 'Royal Peacock', 'diamonds', 150, 'royal', 'epic', 'peacock', 'swoop', 'wings', 'magic', 170),
  ('golden_phoenix', 'Golden Phoenix', 'diamonds', 200, 'legendary', 'legendary', 'phoenix', 'swoop', 'wings', 'fire', 180),
  ('royal_dragon', 'Royal Dragon', 'diamonds', 300, 'legendary', 'legendary', 'dragon', 'swoop', 'land', 'magic', 190),
  ('treasure_chest', 'Legendary Treasure Chest', 'diamonds', 500, 'legendary', 'legendary', 'chest', 'arc', 'open', 'diamonds', 200),
  ('sports_car', 'Luxury Sports Car', 'diamonds', 750, 'legendary', 'legendary', 'car', 'drive', 'land', 'sparks', 210),
  ('royal_palace', 'Ultimate Royal Palace', 'diamonds', 1000, 'legendary', 'legendary', 'palace', 'rise', 'land', 'confetti', 220);
