package com.mayoclone.repository;

import com.mayoclone.domain.OrderStatusEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrderStatusEventRepository extends JpaRepository<OrderStatusEvent, Long> {

    /** An order's status timeline, oldest first. */
    List<OrderStatusEvent> findByOrderIdOrderByAtAsc(Long orderId);
}
