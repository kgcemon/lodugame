-- Limited-edition shop items: how many have been sold, counted inside the purchase
-- transaction so a limited item can never be oversold.

create table shop_stock (
  item  text primary key check (char_length(item) between 1 and 60),
  sold  integer not null default 0 check (sold >= 0)
);

alter table shop_stock enable row level security;
