-- Movimentações futuras sem conta nem cartão também são agendadas (nunca alteraram saldo)
UPDATE entries SET applied = FALSE
WHERE mode = 'SIMPLE' AND applied = TRUE
  AND account_id IS NULL AND credit_card_id IS NULL
  AND entry_date > CURRENT_DATE;
