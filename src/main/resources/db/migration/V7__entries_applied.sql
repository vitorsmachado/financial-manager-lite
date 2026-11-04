-- Movimentações com data futura só alteram o saldo da conta quando a data chega
ALTER TABLE entries ADD COLUMN applied BOOLEAN NOT NULL DEFAULT TRUE;
