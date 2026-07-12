package com.mayoclone.repository;

import com.mayoclone.domain.PendingCheckout;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Lookup for the checkout→verify binding. Keyed by Razorpay order id (String PK).
 */
public interface PendingCheckoutRepository extends JpaRepository<PendingCheckout, String> {
}
