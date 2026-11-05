package com.financialmanager.lite.service;

import com.financialmanager.lite.model.CreditCard;
import com.financialmanager.lite.model.Entry;
import com.financialmanager.lite.model.User;
import com.financialmanager.lite.repository.AccountRepository;
import com.financialmanager.lite.repository.CreditCardRepository;
import com.financialmanager.lite.repository.EntryRepository;
import com.financialmanager.lite.service.DashboardService.ExpectedItem;
import com.financialmanager.lite.service.ProjectionService.CashFlowEvent;
import com.financialmanager.lite.service.ProjectionService.DayFlow;
import com.financialmanager.lite.service.ProjectionService.MonthProjection;
import com.financialmanager.lite.service.ProjectionService.ProjectionData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    @Mock private AccountRepository accountRepository;
    @Mock private CreditCardRepository creditCardRepository;
    @Mock private EntryRepository entryRepository;
    @Mock private ProjectionService projectionService;

    @InjectMocks private DashboardService dashboardService;

    private User user;
    private LocalDate today;
    private LocalDate from;

    @BeforeEach
    void setUp() {
        user = new User();
        today = LocalDate.now();
        from = YearMonth.now().atDay(1);
    }

    // Stub padrão de "tudo zerado" — cada teste sobrescreve só o que precisa verificar.
    // Usa lenient() porque nem todo teste exercita ou sobrescreve todos os stubs.
    private void stubAllZero() {
        lenient().when(accountRepository.sumBalanceByUser(user)).thenReturn(BigDecimal.ZERO);
        lenient().when(entryRepository.sumSimpleByUserAndTypeAndDateRange(eq(user), eq("INCOME"), any(), any()))
                .thenReturn(BigDecimal.ZERO);
        lenient().when(entryRepository.sumSimpleByUserAndTypeAndDateRange(eq(user), eq("EXPENSE"), any(), any()))
                .thenReturn(BigDecimal.ZERO);
        lenient().when(entryRepository.sumRemainingPayablesByUser(user)).thenReturn(BigDecimal.ZERO);
        lenient().when(entryRepository.sumRemainingReceivablesByUser(user)).thenReturn(BigDecimal.ZERO);
        lenient().when(creditCardRepository.findByUserAndActiveTrue(user)).thenReturn(List.of());
        lenient().when(entryRepository.sumExpensesByCategory(eq(user), any(), any())).thenReturn(List.of());
        lenient().when(entryRepository.findActivePayablesByUser(user)).thenReturn(List.of());
        lenient().when(entryRepository.findActiveReceivablesByUser(user)).thenReturn(List.of());
    }

    private MonthProjection month(YearMonth ym, String opening, String closing, CashFlowEvent... events) {
        return new MonthProjection(ym, new BigDecimal(opening),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, new BigDecimal(closing), List.of(events), List.of());
    }

    private void stubCurrentMonth(List<DayFlow> flow, String monthEndBalance) {
        when(projectionService.compute(user, null, 0, 1)).thenReturn(new ProjectionData(
                BigDecimal.ZERO, List.of(month(YearMonth.now(), "0", monthEndBalance)), null,
                flow, YearMonth.now(), 0, 1));
    }

    private void stubFutureMonth(int monthsAhead, MonthProjection target) {
        List<MonthProjection> months = new ArrayList<>();
        for (int i = 0; i < monthsAhead; i++) {
            months.add(month(YearMonth.now().plusMonths(i), "0", "0"));
        }
        months.add(target);
        when(projectionService.compute(user, null, 0, monthsAhead)).thenReturn(new ProjectionData(
                BigDecimal.ZERO, months, null, List.of(), YearMonth.now(), 0, monthsAhead));
    }

    private DayFlow day(LocalDate date, String opening, String closing, CashFlowEvent... events) {
        return new DayFlow(date, date.equals(today), date.isBefore(today),
                new BigDecimal(opening), List.of(events), new BigDecimal(closing));
    }

    private CashFlowEvent event(int day, String description, String type, String amount) {
        return new CashFlowEvent(day, description, type, new BigDecimal(amount), "Avulso", "bg-secondary", null);
    }

    private CreditCard cardWithUsedAmount(BigDecimal usedAmount) {
        return CreditCard.builder()
                .user(user).name("Cartão").creditLimit(new BigDecimal("5000"))
                .usedAmount(usedAmount).closingDay(10).dueDay(20).build();
    }

    @SuppressWarnings("unchecked")
    private List<ExpectedItem> items(Map<String, Object> summary, String key) {
        return (List<ExpectedItem>) summary.get(key);
    }

    @Nested
    @DisplayName("hoje")
    class Today {

        @Test
        @DisplayName("sem data usa hoje e soma o confirmado do dia 1 até hoje")
        void buildsConfirmedSummaryForToday() {
            stubAllZero();
            stubCurrentMonth(List.of(), "5000.00");
            when(accountRepository.sumBalanceByUser(user)).thenReturn(new BigDecimal("5000.00"));
            when(entryRepository.sumSimpleByUserAndTypeAndDateRange(user, "INCOME", from, today))
                    .thenReturn(new BigDecimal("1000.00"));
            when(entryRepository.sumSimpleByUserAndTypeAndDateRange(user, "EXPENSE", from, today))
                    .thenReturn(new BigDecimal("700.00"));
            when(entryRepository.sumRemainingPayablesByUser(user)).thenReturn(new BigDecimal("300.00"));
            when(entryRepository.sumRemainingReceivablesByUser(user)).thenReturn(new BigDecimal("150.00"));
            when(creditCardRepository.findByUserAndActiveTrue(user))
                    .thenReturn(List.of(cardWithUsedAmount(new BigDecimal("400.00"))));
            when(entryRepository.findActivePayablesByUser(user)).thenReturn(List.of(new Entry(), new Entry()));
            when(entryRepository.findActiveReceivablesByUser(user)).thenReturn(List.of(new Entry()));

            Map<String, Object> summary = dashboardService.getSummary(user, null);

            assertThat(summary.get("date")).isEqualTo(today);
            assertThat(summary.get("isToday")).isEqualTo(true);
            assertThat(summary.get("periodFrom")).isEqualTo(from);
            assertThat(summary.get("balanceAtDate")).isEqualTo(new BigDecimal("5000.00"));
            assertThat(summary.get("periodIncome")).isEqualTo(new BigDecimal("1000.00"));
            assertThat(summary.get("periodExpenses")).isEqualTo(new BigDecimal("700.00"));
            assertThat((BigDecimal) summary.get("periodNet")).isEqualByComparingTo("300.00");
            assertThat((Double) summary.get("savingsRate")).isEqualTo(30.0);
            assertThat(summary.get("totalCardUsed")).isEqualTo(new BigDecimal("400.00"));
            assertThat(summary.get("totalReceivable")).isEqualTo(new BigDecimal("150.00"));
            assertThat(summary.get("totalDebt")).isEqualTo(new BigDecimal("300.00"));
            assertThat(summary.get("activeDebts")).isEqualTo(2L);
            assertThat(summary.get("activeReceivables")).isEqualTo(1L);
            assertThat(summary.get("activeCards")).isEqualTo(1L);
        }

        @Test
        @DisplayName("savingsRate = 0 quando não há entradas (evita divisão por zero)")
        void savingsRateIsZeroWhenNoIncome() {
            stubAllZero();
            stubCurrentMonth(List.of(), "0");

            Map<String, Object> summary = dashboardService.getSummary(user, null);

            assertThat((Double) summary.get("savingsRate")).isEqualTo(0.0);
            assertThat((BigDecimal) summary.get("periodNet")).isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("totalCardUsed soma usedAmount de múltiplos cartões ativos")
        void sumsUsedAmountAcrossMultipleCards() {
            stubAllZero();
            stubCurrentMonth(List.of(), "0");
            when(creditCardRepository.findByUserAndActiveTrue(user)).thenReturn(List.of(
                    cardWithUsedAmount(new BigDecimal("100.00")),
                    cardWithUsedAmount(new BigDecimal("250.00"))
            ));

            Map<String, Object> summary = dashboardService.getSummary(user, null);

            assertThat((BigDecimal) summary.get("totalCardUsed")).isEqualByComparingTo("350.00");
            assertThat(summary.get("activeCards")).isEqualTo(2L);
        }

        @Test
        @DisplayName("expenseByCategory converte linhas Object[] em mapa categoria→valor")
        void buildsExpenseByCategoryMap() {
            stubAllZero();
            stubCurrentMonth(List.of(), "0");
            when(entryRepository.sumExpensesByCategory(user, from, today)).thenReturn(List.of(
                    new Object[]{"Alimentação", new BigDecimal("300.00")},
                    new Object[]{"Transporte", new BigDecimal("150.00")}
            ));

            Map<String, Object> summary = dashboardService.getSummary(user, null);

            @SuppressWarnings("unchecked")
            Map<String, BigDecimal> byCategory = (Map<String, BigDecimal>) summary.get("expenseByCategory");
            assertThat(byCategory)
                    .containsEntry("Alimentação", new BigDecimal("300.00"))
                    .containsEntry("Transporte", new BigDecimal("150.00"));
        }

        @Test
        @DisplayName("esperado inclui os eventos ainda não lançados de hoje e ignora dias passados")
        void includesTodaysPendingEvents() {
            stubAllZero();
            List<DayFlow> flow = new ArrayList<>();
            if (today.getDayOfMonth() > 1) {
                LocalDate yesterday = today.minusDays(1);
                flow.add(day(yesterday, "900", "1000", event(yesterday.getDayOfMonth(), "Ontem", "INCOME", "100")));
            }
            flow.add(day(today, "1000", "700", event(today.getDayOfMonth(), "Hoje", "EXPENSE", "300")));
            stubCurrentMonth(flow, "700");

            Map<String, Object> summary = dashboardService.getSummary(user, null);

            assertThat((BigDecimal) summary.get("expectedIncome")).isEqualByComparingTo("0");
            assertThat((BigDecimal) summary.get("expectedExpenses")).isEqualByComparingTo("300");
        }

        @Test
        @DisplayName("faturas vencidas (fechamento do mês abaixo do fluxo) entram como saída esperada")
        void overdueCardBillsCountAsExpectedExpense() {
            stubAllZero();
            stubCurrentMonth(List.of(day(today, "1000", "1000")), "600");

            Map<String, Object> summary = dashboardService.getSummary(user, null);

            assertThat(items(summary, "expectedItems")).extracting(ExpectedItem::description)
                    .containsExactly("Faturas vencidas em aberto");
            assertThat((BigDecimal) summary.get("expectedExpenses")).isEqualByComparingTo("400");
            assertThat((BigDecimal) summary.get("monthEndBalance")).isEqualByComparingTo("600");
        }
    }

    @Nested
    @DisplayName("data passada")
    class Past {

        @Test
        @DisplayName("mostra só o confirmado do dia 1 até a data, sem previsão")
        void showsOnlyConfirmed() {
            LocalDate date = today.minusMonths(2).withDayOfMonth(10);
            LocalDate monthStart = date.withDayOfMonth(1);
            stubAllZero();
            when(accountRepository.sumBalanceByUser(user)).thenReturn(new BigDecimal("1000.00"));
            when(entryRepository.sumAccountNetChangeAfter(user, date, today)).thenReturn(new BigDecimal("-200.00"));
            when(entryRepository.sumSimpleByUserAndTypeAndDateRange(user, "EXPENSE", monthStart, date))
                    .thenReturn(new BigDecimal("80.00"));

            Map<String, Object> summary = dashboardService.getSummary(user, date);

            assertThat(summary.get("isPast")).isEqualTo(true);
            assertThat(summary.get("periodFrom")).isEqualTo(monthStart);
            assertThat((BigDecimal) summary.get("balanceAtDate")).isEqualByComparingTo("1200.00"); // 1000-(-200)
            assertThat((BigDecimal) summary.get("periodExpenses")).isEqualByComparingTo("80.00");
            assertThat(items(summary, "expectedItems")).isEmpty();
            verifyNoInteractions(projectionService);
        }

        @Test
        @DisplayName("dia anterior no mês atual também não mostra previsão")
        void earlierDayInCurrentMonthHasNoForecast() {
            assumeTrue(today.getDayOfMonth() > 1);
            stubAllZero();
            when(entryRepository.sumAccountNetChangeAfter(user, today.minusDays(1), today))
                    .thenReturn(BigDecimal.ZERO);

            Map<String, Object> summary = dashboardService.getSummary(user, today.minusDays(1));

            assertThat(summary.get("isPast")).isEqualTo(true);
            verifyNoInteractions(projectionService);
        }
    }

    @Nested
    @DisplayName("data futura")
    class Future {

        @Test
        @DisplayName("no mês atual: saldo = hoje + previstos até a data; o resto fica como esperado")
        void splitsItemsInCurrentMonth() {
            assumeTrue(today.plusDays(2).getMonth() == today.getMonth());
            LocalDate date  = today.plusDays(1);
            LocalDate later = today.plusDays(2);
            stubAllZero();
            when(accountRepository.sumBalanceByUser(user)).thenReturn(new BigDecimal("1000.00"));
            stubCurrentMonth(List.of(
                    day(today, "1000", "1000"),
                    day(date,  "1000", "900", event(date.getDayOfMonth(), "Conta", "EXPENSE", "100")),
                    day(later, "900",  "950", event(later.getDayOfMonth(), "Pix", "INCOME", "50"))
            ), "950");

            Map<String, Object> summary = dashboardService.getSummary(user, date);

            assertThat(summary.get("isFuture")).isEqualTo(true);
            assertThat(summary.get("periodFrom")).isEqualTo(today);
            assertThat((BigDecimal) summary.get("balanceAtDate")).isEqualByComparingTo("900");
            assertThat((BigDecimal) summary.get("periodExpenses")).isEqualByComparingTo("100");
            assertThat(items(summary, "untilItems")).extracting(ExpectedItem::date).containsExactly(date);
            assertThat(items(summary, "expectedItems")).extracting(ExpectedItem::date).containsExactly(later);
            assertThat((BigDecimal) summary.get("expectedIncome")).isEqualByComparingTo("50");
            assertThat((BigDecimal) summary.get("monthEndBalance")).isEqualByComparingTo("950");
            verify(entryRepository, never()).sumExpensesByCategory(any(), any(), any());
        }

        @Test
        @DisplayName("em mês futuro: parte do saldo inicial projetado e limita o dia ao tamanho do mês")
        void usesMonthProjection() {
            YearMonth target = YearMonth.now().plusMonths(2);
            LocalDate date = target.atDay(10);
            stubAllZero();
            stubFutureMonth(2, month(target, "2000", "2150",
                    event(5, "Antes", "EXPENSE", "300"),
                    event(40, "Depois", "INCOME", "450")));

            Map<String, Object> summary = dashboardService.getSummary(user, date);

            assertThat(summary.get("periodFrom")).isEqualTo(target.atDay(1));
            assertThat((BigDecimal) summary.get("balanceAtDate")).isEqualByComparingTo("1700"); // 2000-300
            assertThat((BigDecimal) summary.get("periodExpenses")).isEqualByComparingTo("300");
            assertThat((BigDecimal) summary.get("expectedIncome")).isEqualByComparingTo("450");
            assertThat(items(summary, "expectedItems")).extracting(ExpectedItem::date)
                    .containsExactly(target.atEndOfMonth());
            assertThat((BigDecimal) summary.get("monthEndBalance")).isEqualByComparingTo("2150");
        }

        @Test
        @DisplayName("data além do limite de 24 meses volta para hoje")
        void beyondLimitFallsBackToToday() {
            stubAllZero();
            stubCurrentMonth(List.of(), "0");

            Map<String, Object> summary = dashboardService.getSummary(user, today.plusMonths(25));

            assertThat(summary.get("date")).isEqualTo(today);
        }

        @Test
        @DisplayName("no último mês permitido não oferece próximo mês")
        void noNextMonthAtTheLimit() {
            YearMonth last = YearMonth.now().plusMonths(24);
            stubAllZero();
            stubFutureMonth(24, month(last, "0", "0"));

            Map<String, Object> summary = dashboardService.getSummary(user, last.atDay(1));

            assertThat(summary.get("date")).isEqualTo(last.atDay(1));
            assertThat(summary.get("nextMonth")).isNull();
        }
    }
}
