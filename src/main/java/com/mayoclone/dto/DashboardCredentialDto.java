package com.mayoclone.dto;

import com.mayoclone.domain.VendorDashboardCredential;

import java.time.Instant;

/**
 * Safe view of a vendor's dashboard credential. Deliberately WRITE-ONLY for secrets:
 * it NEVER exposes the username, password or captured session token — only whether a
 * credential is configured, its status, the session expiry and the last error (for a
 * "needs re-auth" badge in the UI).
 */
public record DashboardCredentialDto(
        boolean configured,
        String status,
        Instant sessionExpiresAt,
        String lastError
) {

    /** The "not configured" view (no credential row for this vendor). */
    public static DashboardCredentialDto notConfigured() {
        return new DashboardCredentialDto(false, null, null, null);
    }

    public static DashboardCredentialDto from(VendorDashboardCredential c) {
        if (c == null) {
            return notConfigured();
        }
        return new DashboardCredentialDto(
                true,
                c.getStatus() == null ? null : c.getStatus().name(),
                c.getSessionExpiresAt(),
                c.getLastError()
        );
    }
}
