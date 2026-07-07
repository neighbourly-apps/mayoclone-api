package com.mayoclone.repository;

import com.mayoclone.domain.EmailOtp;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface EmailOtpRepository extends JpaRepository<EmailOtp, Long> {

    /** The active (unconsumed) code for an email — at most one exists at a time. */
    Optional<EmailOtp> findFirstByEmailAndConsumedFalseOrderByCreatedAtDesc(String email);

    /** Invalidate any prior unconsumed codes for an email before issuing a new one. */
    @Modifying
    @Query("update EmailOtp e set e.consumed = true where e.email = :email and e.consumed = false")
    void consumeAllForEmail(@Param("email") String email);
}
