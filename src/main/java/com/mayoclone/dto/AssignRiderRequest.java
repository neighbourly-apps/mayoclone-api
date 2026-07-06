package com.mayoclone.dto;

/** Body for {@code PATCH /api/orders/{id}/assign}. A null {@code riderId} clears the assignment. */
public record AssignRiderRequest(Long riderId) {
}
