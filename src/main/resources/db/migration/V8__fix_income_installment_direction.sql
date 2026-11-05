-- Parcelamentos de receita eram gravados como PAYABLE e contavam como despesa
UPDATE entries SET direction = 'RECEIVABLE'
WHERE mode = 'INSTALLMENT' AND type = 'INCOME' AND direction <> 'RECEIVABLE';
