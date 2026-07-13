package com.mayoclone.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code PUT /api/vendors/{id}/dashboard-credential}. Both fields are
 * required; they are encrypted at rest and NEVER returned.
 */
public record UpsertDashboardCredentialRequest(
        @NotBlank String username,
        @NotBlank String password
) {
}
