package com.financialmanager.lite.service;

import com.financialmanager.lite.model.CreditCard;
import com.financialmanager.lite.model.Entry;
import com.financialmanager.lite.model.User;
import com.financialmanager.lite.repository.AccountRepository;
import com.financialmanager.lite.repository.CreditCardRepository;
import com.financialmanager.lite.repository.EntryRepository;
import com.financialmanager.lite.service.ProjectionService.CashFlowEvent;
import com.financialmanager.lite.service.ProjectionService.DayFlow;
import com.financialmanager.lite.service.ProjectionService.MonthProjection;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DashboardService {

    private static final int MAX_MONTHS_AHEAD = 24;

    private final AccountRepository accountRepository;
    private final CreditCardRepository creditCardRepository;
    private final EntryRepository entryRepository;
    private final ProjectionService projectionService;

    /** Item previsto no mês da data escolhida. */
    public record ExpectedItem(LocalDate date, String description, String type,
                               BigDecimal amount, String originLabel, String badgeClass) {}

    private record Forecast(List<ExpectedItem> items, BigDecimal opening, BigDecimal closing) {}

    /**
     * Retrato financeiro em uma data:
     * passado mostra só o confirmado até a data, hoje mostra o confirmado e o esperado até o fim do mês,
     * futuro mostra só a previsão.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getSummary(User user, LocalDate requested) {
        LocalDate today   = LocalDate.now();
        LocalDate maxDate = YearMonth.from(today).plusMonths(MAX_MONTHS_AHEAD).atEndOfMonth();
        LocalDate date    = (requested == null || requested.isAfter(maxDate)) ? today : requested;
        YearMonth month   = YearMonth.from(date);
        boolean past      = date.isBefore(today);
        boolean future    = date.isAfter(today);

        BigDecimal currentBalance = accountRepository.sumBalanceByUser(user);
        LocalDate periodFrom = month.atDay(1);
        BigDecimal balanceAtDate = currentBalance;
        BigDecimal periodIncome;
        BigDecimal periodExpenses;
        Map<String, BigDecimal> expenseByCategory = new LinkedHashMap<>();
        List<ExpectedItem> untilItems = List.of();
        List<ExpectedItem> expectedItems = List.of();
        BigDecimal monthEndBalance = null;

        if (future) {
            Forecast forecast = loadForecast(user, month, today, currentBalance);
            untilItems    = forecast.items().stream().filter(it -> !it.date().isAfter(date)).toList();
            expectedItems = forecast.items().stream().filter(it -> it.date().isAfter(date)).toList();
            periodIncome   = sumIncome(untilItems);
            periodExpenses = sumExpenses(untilItems);
            balanceAtDate  = forecast.opening().add(periodIncome).subtract(periodExpenses);
            monthEndBalance = forecast.closing();
            if (month.equals(YearMonth.from(today))) periodFrom = today;
        } else {
            periodIncome   = entryRepository.sumSimpleByUserAndTypeAndDateRange(user, "INCOME",  periodFrom, date);
            periodExpenses = entryRepository.sumSimpleByUserAndTypeAndDateRange(user, "EXPENSE", periodFrom, date);
            for (Object[] row : entryRepository.sumExpensesByCategory(user, periodFrom, date)) {
                expenseByCategory.put((String) row[0], (BigDecimal) row[1]);
            }
            if (past) {
                balanceAtDate = currentBalance.subtract(entryRepository.sumAccountNetChangeAfter(user, date, today));
            } else {
                Forecast forecast = loadForecast(user, month, today, currentBalance);
                expectedItems   = forecast.items();
                monthEndBalance = forecast.closing();
            }
        }

        BigDecimal periodNet = periodIncome.subtract(periodExpenses);
        double savingsRate = periodIncome.compareTo(BigDecimal.ZERO) > 0
                ? periodNet.divide(periodIncome, 4, RoundingMode.HALF_EVEN).multiply(BigDecimal.valueOf(100)).doubleValue()
                : 0.0;

        // ── Visão geral (valores de hoje, independe da data) ────────────────
        List<CreditCard> cards = creditCardRepository.findByUserAndActiveTrue(user);
        BigDecimal totalCardUsed = cards.stream()
                .map(CreditCard::getUsedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Totais têm fim: parcelamentos + agendadas. Recorrentes não têm fim e aparecem como valor por mês.
        List<Entry> scheduled = entryRepository.findPendingSimpleByUser(user);
        List<Entry> monthlyRecurring = entryRepository.findRecurringActiveByUser(user).stream()
                .filter(e -> "MONTHLY".equals(e.getFrequency()))
                .toList();

        LocalDate nextMonth = date.plusMonths(1);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("date",              date);
        summary.put("today",             today);
        summary.put("isPast",            past);
        summary.put("isToday",           date.equals(today));
        summary.put("isFuture",          future);
        summary.put("maxDate",           maxDate);
        summary.put("prevMonth",         date.minusMonths(1));
        summary.put("nextMonth",         nextMonth.isAfter(maxDate) ? null : nextMonth);
        summary.put("monthEnd",          month.atEndOfMonth());
        summary.put("periodFrom",        periodFrom);
        summary.put("balanceAtDate",     balanceAtDate);
        summary.put("periodIncome",      periodIncome);
        summary.put("periodExpenses",    periodExpenses);
        summary.put("periodNet",         periodNet);
        summary.put("savingsRate",       savingsRate);
        summary.put("expenseByCategory", expenseByCategory);
        summary.put("untilItems",        untilItems);
        summary.put("expectedItems",     expectedItems);
        summary.put("expectedIncome",    sumIncome(expectedItems));
        summary.put("expectedExpenses",  sumExpenses(expectedItems));
        summary.put("monthEndBalance",   monthEndBalance);
        summary.put("totalCardUsed",     totalCardUsed);
        summary.put("activeCards",       (long) cards.size());
        summary.put("totalDebt",         entryRepository.sumRemainingPayablesByUser(user).add(sumByType(scheduled, "EXPENSE")));
        summary.put("activeDebts",       (long) entryRepository.findActivePayablesByUser(user).size());
        summary.put("totalReceivable",   entryRepository.sumRemainingReceivablesByUser(user).add(sumByType(scheduled, "INCOME")));
        summary.put("activeReceivables", (long) entryRepository.findActiveReceivablesByUser(user).size());
        summary.put("scheduledExpenses", countByType(scheduled, "EXPENSE"));
        summary.put("scheduledIncomes",  countByType(scheduled, "INCOME"));
        summary.put("monthlyRecurringExpense", sumByType(monthlyRecurring, "EXPENSE"));
        summary.put("monthlyRecurringIncome",  sumByType(monthlyRecurring, "INCOME"));
        return summary;
    }

    /** Previsão do mês vinda do fluxo de caixa: mês atual a partir de hoje, meses futuros inteiros. */
    private Forecast loadForecast(User user, YearMonth month, LocalDate today, BigDecimal currentBalance) {
        int monthsAhead = (int) ChronoUnit.MONTHS.between(YearMonth.from(today), month);
        var projection = projectionService.compute(user, null, 0, Math.max(monthsAhead, 1));
        MonthProjection monthProjection = projection.projections().get(monthsAhead);

        List<ExpectedItem> items = new ArrayList<>();
        if (monthsAhead > 0) {
            for (CashFlowEvent ev : monthProjection.events()) {
                LocalDate day = month.atDay(Math.min(ev.day(), month.lengthOfMonth()));
                items.add(toItem(day, ev));
            }
            return new Forecast(items, monthProjection.openingBalance(), monthProjection.closingBalance());
        }

        List<DayFlow> flow = projection.currentMonthFlow();
        BigDecimal flowEnd = flow.isEmpty() ? currentBalance : flow.get(flow.size() - 1).closingBalance();
        BigDecimal overdueCardBills = flowEnd.subtract(monthProjection.closingBalance()).max(BigDecimal.ZERO);
        if (overdueCardBills.compareTo(BigDecimal.ZERO) > 0) {
            items.add(new ExpectedItem(today, "Faturas vencidas em aberto", "EXPENSE",
                    overdueCardBills, "Fatura", "bg-danger"));
        }
        for (DayFlow df : flow) {
            if (df.isPast()) continue;
            for (CashFlowEvent ev : df.events()) {
                items.add(toItem(df.date(), ev));
            }
        }
        return new Forecast(items, currentBalance, monthProjection.closingBalance());
    }

    private BigDecimal sumByType(List<Entry> entries, String type) {
        return entries.stream().filter(e -> type.equals(e.getType()))
                .map(Entry::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private long countByType(List<Entry> entries, String type) {
        return entries.stream().filter(e -> type.equals(e.getType())).count();
    }

    private ExpectedItem toItem(LocalDate date, CashFlowEvent ev) {
        return new ExpectedItem(date, ev.description(), ev.type(), ev.amount(), ev.originLabel(), ev.badgeClass());
    }

    private BigDecimal sumIncome(List<ExpectedItem> items) {
        return items.stream().filter(it -> "INCOME".equals(it.type()))
                .map(ExpectedItem::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal sumExpenses(List<ExpectedItem> items) {
        return items.stream().filter(it -> !"INCOME".equals(it.type()))
                .map(ExpectedItem::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
