package com.mayoclone.service;

import com.mayoclone.domain.IrctcOrder;
import com.mayoclone.repository.AggregatorRepository;
import com.mayoclone.repository.IrctcOrderRepository;
import com.mayoclone.repository.OrderStatusEventRepository;
import com.mayoclone.repository.RiderRepository;
import com.mayoclone.repository.VendorRepository;
import com.mayoclone.security.CurrentAccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Proves tenant isolation is enforced by the SERVICE using the id from the
 * SecurityContext — never from the caller's request — and that cross-tenant ids
 * surface as 404 (not 403) to avoid enumeration.
 */
class OrderServiceTenantScopingTest {

    private IrctcOrderRepository orderRepo;
    private CurrentAccountService currentAccount;
    private OrderService service;

    @BeforeEach
    void setUp() {
        orderRepo = mock(IrctcOrderRepository.class);
        AggregatorRepository aggregatorRepo = mock(AggregatorRepository.class);
        VendorRepository vendorRepo = mock(VendorRepository.class);
        currentAccount = mock(CurrentAccountService.class);
        RiderRepository riderRepo = mock(RiderRepository.class);
        OrderStatusEventRepository eventRepo = mock(OrderStatusEventRepository.class);
        when(eventRepo.findByOrderIdOrderByAtAsc(any())).thenReturn(List.of());
        OrderMapper mapper = new OrderMapper(riderRepo, eventRepo);
        service = new OrderService(orderRepo, aggregatorRepo, vendorRepo, currentAccount, mapper);
    }

    @Test
    void listQueriesOnlyTheCallersAccount() {
        when(currentAccount.accountId()).thenReturn(55L);
        when(orderRepo.findByAccountIdOrderByPlacedAtDesc(55L)).thenReturn(List.of());

        service.list(null, null, null, null, null, null, null, null, null);

        verify(orderRepo).findByAccountIdOrderByPlacedAtDesc(eq(55L));
    }

    @Test
    void getScopesByAccountAndReturnsDtoWhenOwned() {
        when(currentAccount.accountId()).thenReturn(10L);
        IrctcOrder order = new IrctcOrder();
        order.setId(1L);
        order.setAccountId(10L);
        when(orderRepo.findByIdAndAccountId(1L, 10L)).thenReturn(Optional.of(order));

        assertEquals(1L, service.get(1L).id());
        verify(orderRepo).findByIdAndAccountId(eq(1L), eq(10L));
    }

    @Test
    void crossTenantOrderIsNotFoundNot403() {
        when(currentAccount.accountId()).thenReturn(10L);
        // Order id 1 belongs to another tenant → scoped query returns empty.
        when(orderRepo.findByIdAndAccountId(1L, 10L)).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.get(1L));
        assertEquals(404, ex.getStatusCode().value());
    }
}
