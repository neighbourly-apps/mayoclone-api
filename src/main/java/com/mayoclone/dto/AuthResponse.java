package com.mayoclone.dto;

/** Body returned by register/login: a short-lived access token + the account. */
public record AuthResponse(String accessToken, AccountDto account) {
}
