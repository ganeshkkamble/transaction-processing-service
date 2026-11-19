
package com.fde.transaction.config;

import java.math.BigDecimal;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fde.transaction.entity.Account;
import com.fde.transaction.repository.AccountRepository;

@Configuration
public class AccountDataInitializer {

    @Bean
    CommandLineRunner initializeAccounts(AccountRepository repository) {

        return args -> {

            createAccount(repository, "ACC-201", "0.00");
            createAccount(repository, "ACC-202", "1000.00");
            createAccount(repository, "ACC-203", "0.00");

            System.out.println("Test accounts initialized successfully");
        };
    }

    private void createAccount(
            AccountRepository repository,
            String accountId,
            String openingBalance) {

        if (!repository.existsById(accountId)) {

            Account account = new Account();

            account.setAccountId(accountId);
            account.setBalance(new BigDecimal(openingBalance));
            account.setLastSequence(0L);

            repository.save(account);
        }
    }
}
