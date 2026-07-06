package com.mayoclone.service;

import com.mayoclone.domain.IrctcOrder;
import com.mayoclone.domain.Rider;
import com.mayoclone.dto.OrderDto;
import com.mayoclone.dto.StatusEventDto;
import com.mayoclone.repository.OrderStatusEventRepository;
import com.mayoclone.repository.RiderRepository;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Assembles a full {@link OrderDto} from an {@link IrctcOrder}, resolving the
 * assigned rider's name and the order's status timeline from their repositories.
 */
@Component
public class OrderMapper {

    private final RiderRepository riderRepo;
    private final OrderStatusEventRepository eventRepo;

    public OrderMapper(RiderRepository riderRepo, OrderStatusEventRepository eventRepo) {
        this.riderRepo = riderRepo;
        this.eventRepo = eventRepo;
    }

    public OrderDto toDto(IrctcOrder o) {
        String riderName = null;
        if (o.getRiderId() != null) {
            riderName = riderRepo.findByIdAndAccountId(o.getRiderId(), o.getAccountId())
                    .map(Rider::getName).orElse(null);
        }
        List<StatusEventDto> history = o.getId() == null ? List.of()
                : eventRepo.findByOrderIdOrderByAtAsc(o.getId()).stream()
                        .map(StatusEventDto::from).toList();
        return OrderDto.from(o, riderName, history);
    }
}
