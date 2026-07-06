package com.mayoclone.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * An {@code Account} is a restaurant business: it is BOTH the tenant boundary
 * (all vendors/orders are isolated per account) AND the login identity
 * (email + password). Business-profile fields used on invoices (station, gstin,
 * address) live here; mailbox-specific fields live on {@link Vendor}.
 *
 * <p>{@code passwordHash} is an Argon2 hash and is NEVER exposed in any DTO or
 * {@code toString()}.
 */
@Entity
@Table(name = "account")
public class Account {

    /** Role values, stored as strings. */
    public static final String ROLE_OWNER = "OWNER";
    public static final String ROLE_ADMIN = "ADMIN";

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DISABLED = "DISABLED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String businessName;

    /** Login identity. Unique, lower-cased on write. */
    @Column(nullable = false, unique = true)
    private String email;

    /** Argon2 password hash. Never exposed. */
    @Column(nullable = false)
    private String passwordHash;

    @Column(nullable = true)
    private String stationCode;

    @Column(nullable = true)
    private String stationName;

    @Column(nullable = true)
    private String gstin;

    @Column(nullable = true)
    private String addressLine;

    @Column(nullable = true)
    private String phone;

    /** {@link #ROLE_OWNER} (default) or {@link #ROLE_ADMIN}. */
    @Column(nullable = false)
    private String role = ROLE_OWNER;

    @Column(nullable = false)
    private String status = STATUS_ACTIVE;

    @Column(nullable = false)
    private Instant createdAt;

    public Account() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getBusinessName() {
        return businessName;
    }

    public void setBusinessName(String businessName) {
        this.businessName = businessName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getStationCode() {
        return stationCode;
    }

    public void setStationCode(String stationCode) {
        this.stationCode = stationCode;
    }

    public String getStationName() {
        return stationName;
    }

    public void setStationName(String stationName) {
        this.stationName = stationName;
    }

    public String getGstin() {
        return gstin;
    }

    public void setGstin(String gstin) {
        this.gstin = gstin;
    }

    public String getAddressLine() {
        return addressLine;
    }

    public void setAddressLine(String addressLine) {
        this.addressLine = addressLine;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    /** NEVER leak the password hash. */
    @Override
    public String toString() {
        return "Account{id=" + id + ", email='" + email + "', role='" + role + "'}";
    }
}
