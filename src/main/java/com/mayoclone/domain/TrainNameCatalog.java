package com.mayoclone.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A learned mapping from a train number to its name, shared across all tenants.
 *
 * <p>Train names are public and universal, so when one aggregator's email gives us
 * "12190 / MAHAKAUSHAL EXP" we remember it here and use it to fill the blank name on
 * orders from aggregators that send only the bare number. See {@code TrainNameService}
 * and the ingestion pipeline for how it is populated and consumed.
 */
@Entity
@Table(name = "train_name_catalog")
public class TrainNameCatalog {

    @Id
    @Column(name = "train_number", length = 5)
    private String trainNumber;

    @Column(name = "train_name", nullable = false, length = 120)
    private String trainName;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected TrainNameCatalog() {
    }

    public TrainNameCatalog(String trainNumber, String trainName) {
        this.trainNumber = trainNumber;
        this.trainName = trainName;
        this.updatedAt = Instant.now();
    }

    public String getTrainNumber() {
        return trainNumber;
    }

    public void setTrainNumber(String trainNumber) {
        this.trainNumber = trainNumber;
    }

    public String getTrainName() {
        return trainName;
    }

    public void setTrainName(String trainName) {
        this.trainName = trainName;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
