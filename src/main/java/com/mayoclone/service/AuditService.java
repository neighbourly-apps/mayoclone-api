package com.mayoclone.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mayoclone.domain.AuditLog;
import com.mayoclone.repository.AuditLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.Map;

/**
 * Writes append-only {@link AuditLog} rows. Immutability is enforced in Postgres
 * (an UPDATE/DELETE trigger — see the audit migration); here we only ever insert.
 *
 * <p>IP and User-Agent are pulled from the current servlet request when available.
 * Callers MUST NOT pass secrets/tokens/passwords in {@code details}.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private static final int MAX_DETAILS = 2000;
    private static final int MAX_UA = 512;

    private final AuditLogRepository repo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AuditService(AuditLogRepository repo) {
        this.repo = repo;
    }

    /** Full form. Auditing must never break the primary flow, so failures are swallowed (logged). */
    public void record(Long accountId, String actorEmail, String action,
                       String targetType, String targetId, Map<String, ?> details) {
        try {
            AuditLog e = new AuditLog();
            e.setAccountId(accountId);
            e.setActorEmail(actorEmail);
            e.setAction(action);
            e.setTargetType(targetType);
            e.setTargetId(targetId);
            e.setDetailsJson(toJson(details));

            HttpServletRequest req = currentRequest();
            if (req != null) {
                e.setIp(clientIp(req));
                e.setUserAgent(truncate(req.getHeader("User-Agent"), MAX_UA));
            }
            e.setCreatedAt(Instant.now());
            repo.save(e);
        } catch (RuntimeException ex) {
            // Auditing is best-effort — never let it fail the operation being audited.
            log.warn("Failed to write audit log for action {}: {}", action, ex.getMessage());
        }
    }

    public void record(Long accountId, String actorEmail, String action,
                       String targetType, String targetId) {
        record(accountId, actorEmail, action, targetType, targetId, null);
    }

    private String toJson(Map<String, ?> details) {
        if (details == null || details.isEmpty()) {
            return null;
        }
        try {
            return truncate(objectMapper.writeValueAsString(details), MAX_DETAILS);
        } catch (Exception e) {
            return null;
        }
    }

    private static HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest();
        }
        return null;
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
