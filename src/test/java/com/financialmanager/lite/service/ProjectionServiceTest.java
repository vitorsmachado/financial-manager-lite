package com.financialmanager.lite.service;

import com.financialmanager.lite.model.CreditCard;
import com.financialmanager.lite.model.Entry;
import com.financialmanager.lite.model.User;
import com.financialmanager.lite.repository.AccountRepository;
import com.financialmanager.lite.repository.CreditCardRepository;
import com.financialmanager.lite.repository.EntryInstallmentRepository;
import com.financialmanager.lite.repository.EntryRepository;
import com.financialmanager.lite.service.ProjectionService.MonthProjection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectionServiceTest {

    @Mock private AccountRepository accountRepository;
    @Mock private CreditCardRepository creditCardRepository;
    @Mock private EntryRepository entryRepository;
    @Mock private EntryInstallmentRepository installmentRepository;
    @Mock private EntryService entryService;

    @InjectMocks private ProjectionService projectionService;

    private User user;
    private YearMonth now;

    @BeforeEach
    void setUp() {
        user = new User();
        now = YearMonth.now();
        when(accountRepository.sumBalanceByUser(user)).thenReturn(BigDecimal.ZERO);
    }

    // Fechamento 10 e vencimento 20: a fatura vence no próprio mês em que fecha.
    private CreditCard card(String usedAmount) {
        CreditCard card = CreditCard.builder()
                .user(user).name("Cartão").creditLimit(new BigDecimal("10000"))
                .usedAmount(new BigDecimal(usedAmount)).closingDay(10).dueDay(20).build();
        ReflectionTestUtils.setField(card, "id", 1L);
        return card;
    }

    private Entry purchase(CreditCard card, String type, String amount, YearMonth invoice) {
        return Entry.builder().user(user).mode("SIMPLE").type(type).description("Compra")
                .amount(new BigDecimal(amount)).creditCard(card).invoiceMonth(invoice.toString()).build();
    }

    private List<BigDecimal> billsFor(CreditCard card, Entry... entries) {
        when(creditCardRepository.findByUserAndActiveTrue(user)).thenReturn(List.of(card));
        when(entryRepository.findSimpleOnCardsByUser(user)).thenReturn(List.of(entries));
        return projectionService.compute(user, null, 0, 3).projections().stream()
                .map(MonthProjection::cardBills).toList();
    }

    @Nested
    @DisplayName("fatura do cartão por mês de vencimento")
    class CardBills {

        @Test
        @DisplayName("compra de fatura futura sai no mês dela, não na próxima fatura")
        void futureInvoicePurchaseFallsInItsOwnMonth() {
            CreditCard card = card("300");

            List<BigDecimal> bills = billsFor(card, purchase(card, "EXPENSE", "300", now.plusMonths(2)));

            assertThat(bills.get(0)).isEqualByComparingTo("0");
            assertThat(bills.get(1)).isEqualByComparingTo("0");
            assertThat(bills.get(2)).isEqualByComparingTo("300");
        }

        @Test
        @DisplayName("valor sem lançamento correspondente fica no vencimento do mês atual")
        void amountWithoutEntriesStaysInCurrentMonth() {
            List<BigDecimal> bills = billsFor(card("500"));

            assertThat(bills.get(0)).isEqualByComparingTo("500");
            assertThat(bills.get(1)).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("o restante do valor em aberto fica no mês atual e a soma não muda")
        void remainderStaysInCurrentMonth() {
            CreditCard card = card("500");

            List<BigDecimal> bills = billsFor(card, purchase(card, "EXPENSE", "300", now.plusMonths(1)));

            assertThat(bills.get(0)).isEqualByComparingTo("200");
            assertThat(bills.get(1)).isEqualByComparingTo("300");
        }

        @Test
        @DisplayName("pagamento quita a fatura mais antiga e mantém a mais nova")
        void paymentClearsOldestInvoiceFirst() {
            CreditCard card = card("300"); // 500 em compras, 200 já pagos

            List<BigDecimal> bills = billsFor(card,
                    purchase(card, "EXPENSE", "200", now.plusMonths(1)),
                    purchase(card, "EXPENSE", "300", now.plusMonths(2)));

            assertThat(bills.get(1)).isEqualByComparingTo("0");
            assertThat(bills.get(2)).isEqualByComparingTo("300");
        }

        @Test
        @DisplayName("estorno reduz a fatura em que foi lançado")
        void refundReducesItsInvoice() {
            CreditCard card = card("250");

            List<BigDecimal> bills = billsFor(card,
                    purchase(card, "EXPENSE", "300", now.plusMonths(1)),
                    purchase(card, "INCOME", "50", now.plusMonths(1)));

            assertThat(bills.get(1)).isEqualByComparingTo("250");
        }
    }

    @Nested
    @DisplayName("recorrente no cartão")
    class CardRecurring {

        // Dia 23 fica depois do fechamento (10): a cobrança cai na fatura do mês seguinte.
        private Entry recurring(CreditCard card, String lastApplied) {
            return Entry.builder().user(user).mode("RECURRING").type("EXPENSE").description("Claude")
                    .amount(new BigDecimal("100")).frequency("MONTHLY").dayOfMonth(23)
                    .creditCard(card).lastAppliedMonth(lastApplied).build();
        }

        private List<MonthProjection> project(CreditCard card, Entry... recurring) {
            when(creditCardRepository.findByUserAndActiveTrue(user)).thenReturn(List.of(card));
            when(entryRepository.findRecurringActiveByUser(user)).thenReturn(List.of(recurring));
            when(entryService.appliesThisMonth(any(), any())).thenReturn(true);
            return projectionService.compute(user, null, 0, 3).projections();
        }

        @Test
        @DisplayName("entra na fatura em que cai, não como saída fixa no dia da cobrança")
        void goesIntoInvoiceInsteadOfFixedExpense() {
            CreditCard card = card("0");

            List<MonthProjection> months = project(card, recurring(card, null));

            assertThat(months.get(1).recurringExpenses()).isEqualByComparingTo("0");
            assertThat(months.get(1).cardBills()).isEqualByComparingTo("100");
            assertThat(months.get(1).cardBillsRecurring()).isEqualByComparingTo("100");
            assertThat(months.get(1).events()).extracting(ProjectionService.CashFlowEvent::description)
                    .containsExactly("Fatura: Cartão");
            assertThat(months.get(1).events().get(0).day()).isEqualTo(20);
        }

        @Test
        @DisplayName("já lançada no mês não é contada de novo")
        void alreadyAppliedIsNotCountedAgain() {
            CreditCard card = card("0");

            List<MonthProjection> months = project(card, recurring(card, now.toString()));

            // A cobrança deste mês cairia na fatura do mês seguinte; a de lá cai dois meses à frente.
            assertThat(months.get(1).cardBills()).isEqualByComparingTo("0");
            assertThat(months.get(2).cardBills()).isEqualByComparingTo("100");
        }

        @Test
        @DisplayName("sem cartão ativo continua como saída fixa")
        void withoutActiveCardStaysFixedExpense() {
            CreditCard inactive = card("0");
            ReflectionTestUtils.setField(inactive, "id", 99L);
            CreditCard active = card("0");

            List<MonthProjection> months = project(active, recurring(inactive, null));

            assertThat(months.get(1).recurringExpenses()).isEqualByComparingTo("100");
            assertThat(months.get(1).cardBills()).isEqualByComparingTo("0");
        }
    }
}
