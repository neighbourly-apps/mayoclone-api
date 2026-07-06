package com.mayoclone.service;

import com.mayoclone.domain.Aggregator;
import com.mayoclone.domain.IrctcOrder;
import com.mayoclone.domain.OrderItem;
import com.mayoclone.domain.OrderStatus;
import com.mayoclone.domain.OrderType;
import com.mayoclone.domain.PaymentMode;
import com.mayoclone.domain.Vendor;
import com.mayoclone.dto.InvoiceDto;
import com.mayoclone.dto.OrderDto;
import com.mayoclone.dto.OrderStatsDto;
import com.mayoclone.repository.AggregatorRepository;
import com.mayoclone.repository.IrctcOrderRepository;
import com.mayoclone.repository.VendorRepository;
import com.mayoclone.security.CurrentAccountService;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/** Read-side queries, aggregate stats, and invoicing over stored orders. */
@Service
public class OrderService {

    private static final BigDecimal TAX_RATE_PCT = new BigDecimal("5");

    private final IrctcOrderRepository orderRepo;
    private final AggregatorRepository aggregatorRepo;
    private final VendorRepository vendorRepo;
    private final CurrentAccountService currentAccount;
    private final OrderMapper mapper;

    public OrderService(IrctcOrderRepository orderRepo,
                        AggregatorRepository aggregatorRepo,
                        VendorRepository vendorRepo,
                        CurrentAccountService currentAccount,
                        OrderMapper mapper) {
        this.orderRepo = orderRepo;
        this.aggregatorRepo = aggregatorRepo;
        this.vendorRepo = vendorRepo;
        this.currentAccount = currentAccount;
        this.mapper = mapper;
    }

    /**
     * Orders newest first (placedAt desc), scoped to the caller's tenant. All
     * filters optional and combinable; filtering is done in-memory over the
     * account-scoped, ordered result set.
     */
    public List<OrderDto> list(String aggregatorCode, String station, LocalDate date, String trainNumber,
                               OrderStatus status, OrderType orderType, PaymentMode paymentMode, Long riderId) {
        Long accountId = currentAccount.accountId();
        return orderRepo.findByAccountIdOrderByPlacedAtDesc(accountId).stream()
                .filter(o -> aggregatorCode == null
                        || (o.getAggregator() != null
                        && aggregatorCode.equalsIgnoreCase(o.getAggregator().getCode())))
                .filter(o -> station == null || station.equalsIgnoreCase(o.getDeliveryStationCode()))
                .filter(o -> date == null || date.equals(o.getDeliveryDate()))
                .filter(o -> trainNumber == null || trainNumber.equalsIgnoreCase(o.getTrainNumber()))
                .filter(o -> status == null || status == o.getStatus())
                .filter(o -> orderType == null || orderType == o.getOrderType())
                .filter(o -> paymentMode == null || paymentMode == o.getPaymentMode())
                .filter(o -> riderId == null || riderId.equals(o.getRiderId()))
                .map(mapper::toDto)
                .toList();
    }

    public OrderDto get(Long id) {
        return mapper.toDto(find(id));
    }

    public OrderStatsDto stats() {
        Long accountId = currentAccount.accountId();
        List<IrctcOrder> all = orderRepo.findByAccountIdOrderByPlacedAtDesc(accountId);
        BigDecimal revenue = all.stream()
                .map(o -> o.getAmount() != null ? o.getAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Counts come from the managed aggregator table (every aggregator, even zero),
        // scoped to this tenant's orders.
        List<OrderStatsDto.AggregatorCount> byAggregator = aggregatorRepo.findAll().stream()
                .map(agg -> new OrderStatsDto.AggregatorCount(
                        agg.getCode(), agg.getName(), agg.getBrandColor(),
                        orderRepo.countByAccountIdAndAggregator(accountId, agg)))
                .toList();

        long upcomingToday = orderRepo.countByAccountIdAndDeliveryDate(accountId, LocalDate.now());
        return new OrderStatsDto(all.size(), revenue, byAggregator, upcomingToday);
    }

    /**
     * Build a printable invoice for one order. GST on food is 5%.
     *
     * <p>Source of truth for the subtotal: if the order carries an explicit
     * total ({@code amount}) it is used as the subtotal; otherwise we sum the
     * line items. tax = 5% of subtotal, total = subtotal + tax.
     */
    public InvoiceDto invoice(Long id) {
        IrctcOrder o = find(id);

        List<InvoiceDto.Line> lines = o.getItems().stream()
                .map(this::toLine)
                .toList();

        BigDecimal itemsTotal = lines.stream()
                .map(InvoiceDto.Line::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal subTotal = (o.getAmount() != null && o.getAmount().signum() > 0)
                ? o.getAmount()
                : itemsTotal;

        BigDecimal taxAmount = subTotal.multiply(TAX_RATE_PCT)
                .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
        BigDecimal total = subTotal.add(taxAmount);

        Vendor vendor = o.getVendorId() == null ? null
                : vendorRepo.findByIdAndAccountId(o.getVendorId(), o.getAccountId()).orElse(null);
        InvoiceDto.Vendor vendorView = vendor == null ? null : new InvoiceDto.Vendor(
                vendor.getRestaurantName(), vendor.getGstin(), vendor.getAddressLine(),
                vendor.getStationCode(), vendor.getPhone());

        Aggregator agg = o.getAggregator();
        InvoiceDto.Order orderView = new InvoiceDto.Order(
                o.getExternalOrderId(), o.getPnr(), o.getTrainNumber(), o.getTrainName(),
                o.getCoach(), o.getBerth(), o.getDeliveryStationCode(), o.getDeliveryStationName(),
                o.getPassengerName(), o.getDeliveryDate(), o.getDeliverySlot(),
                agg == null ? null : agg.getName());

        return new InvoiceDto(
                String.format("MC-INV-%06d", o.getId()),
                Instant.now(),
                vendorView,
                orderView,
                lines,
                subTotal,
                TAX_RATE_PCT,
                taxAmount,
                total,
                o.getCurrency() != null ? o.getCurrency() : "INR"
        );
    }

    private InvoiceDto.Line toLine(OrderItem item) {
        BigDecimal price = item.getPrice() != null ? item.getPrice() : BigDecimal.ZERO;
        BigDecimal lineTotal = price.multiply(BigDecimal.valueOf(item.getQty()));
        return new InvoiceDto.Line(item.getName(), item.getQty(), price, lineTotal);
    }

    /**
     * Fetch an order scoped to the caller's tenant. Cross-tenant ids return 404
     * (not 403) to avoid leaking the existence of another tenant's orders.
     */
    private IrctcOrder find(Long id) {
        Long accountId = currentAccount.accountId();
        return orderRepo.findByIdAndAccountId(id, accountId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Order " + id + " not found"));
    }
}
