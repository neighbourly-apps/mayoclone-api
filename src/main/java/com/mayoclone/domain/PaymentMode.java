package com.mayoclone.domain;

/** How the passenger pays. Drives the dashboard's online-vs-cod split. */
public enum PaymentMode {
    /** Paid up-front through the aggregator/gateway. */
    PREPAID,
    /** Cash on delivery. */
    COD
}
