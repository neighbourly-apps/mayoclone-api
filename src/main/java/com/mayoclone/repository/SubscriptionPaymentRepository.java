package com.mayoclone.repository;

import com.mayoclone.domain.SubscriptionPayment;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubscriptionPaymentRepository extends JpaRepository<SubscriptionPayment, Long> {

    boolean existsByProviderAndProviderPaymentId(String provider, String providerPaymentId);
}
