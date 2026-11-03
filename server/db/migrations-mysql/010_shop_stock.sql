-- Limited-edition shop items (MySQL/MariaDB): how many have been sold, counted inside the
-- purchase transaction so a limited item can never be oversold.
-- Statements must end with ";" at the end of a line.

create table shop_stock (
  item  varchar(60) not null primary key,
  sold  int not null default 0,
  check (sold >= 0)
) engine = InnoDB default charset = utf8mb4 collate = utf8mb4_0900_ai_ci;
