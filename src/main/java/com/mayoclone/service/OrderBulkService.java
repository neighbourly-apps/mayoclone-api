package com.mayoclone.service;

import com.mayoclone.dto.AssignRiderRequest;
import com.mayoclone.dto.BulkAssignRequest;
import com.mayoclone.dto.BulkResult;
import com.mayoclone.dto.BulkStatusRequest;
import com.mayoclone.dto.UpdateStatusRequest;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;

/**
 * Bulk order operations. Each id is applied independently through
 * {@link OrderCommandService} — so the SAME transition rules, tenant scoping and
 * realtime events are reused per item. Failures (unknown/cross-tenant id, illegal
 * transition, unknown rider) are collected rather than aborting the batch.
 *
 * <p>Not {@code @Transactional}: {@link OrderCommandService} is invoked through its
 * Spring proxy so every item gets its OWN transaction and rolls back in isolation,
 * leaving successful items committed.
 */
@Service
public class OrderBulkService {

    private final OrderCommandService commandService;

    public OrderBulkService(OrderCommandService commandService) {
        this.commandService = commandService;
    }

    public BulkResult bulkStatus(BulkStatusRequest req) {
        List<Long> updated = new ArrayList<>();
        List<BulkResult.Failure> failed = new ArrayList<>();
        UpdateStatusRequest single = new UpdateStatusRequest(req.status(), req.note(), null);
        for (Long id : req.ids()) {
            try {
                commandService.changeStatus(id, single);
                updated.add(id);
            } catch (ResponseStatusException ex) {
                failed.add(new BulkResult.Failure(id, reason(ex)));
            }
        }
        return new BulkResult(updated, failed);
    }

    public BulkResult bulkAssign(BulkAssignRequest req) {
        List<Long> updated = new ArrayList<>();
        List<BulkResult.Failure> failed = new ArrayList<>();
        AssignRiderRequest single = new AssignRiderRequest(req.riderId());
        for (Long id : req.ids()) {
            try {
                commandService.assign(id, single);
                updated.add(id);
            } catch (ResponseStatusException ex) {
                failed.add(new BulkResult.Failure(id, reason(ex)));
            }
        }
        return new BulkResult(updated, failed);
    }

    private static String reason(ResponseStatusException ex) {
        return ex.getReason() != null ? ex.getReason() : ex.getStatusCode().toString();
    }
}
