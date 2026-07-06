package com.mayoclone.repository;

import com.mayoclone.domain.MailAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MailAccountRepository extends JpaRepository<MailAccount, Long> {

    List<MailAccount> findByActiveTrue();
}
