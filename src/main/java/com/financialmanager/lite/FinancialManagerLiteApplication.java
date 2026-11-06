package com.financialmanager.lite;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

@SpringBootApplication
@EnableScheduling
public class FinancialManagerLiteApplication {
    public static void main(String[] args) {
        // "Hoje" e os agendamentos seguem Brasília, mesmo em servidor configurado em UTC
        TimeZone.setDefault(TimeZone.getTimeZone("America/Sao_Paulo"));
        SpringApplication.run(FinancialManagerLiteApplication.class, args);
    }
}
