package fr.u.bordeaux.iut.ddd.accounting.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "quota_reservation")
public class QuotaReservation {

    @Id
    @Column(nullable = false, length = 128)
    private String requestId;

    @Column(nullable = false)
    private Long cloudId;

    @Column(nullable = false, length = 128)
    private String userName;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    public QuotaReservation() {
    }

    public QuotaReservation(String requestId, Long cloudId, String userName, String status) {
        this.requestId = requestId;
        this.cloudId = cloudId;
        this.userName = userName;
        this.status = status;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public String getRequestId() {
        return requestId;
    }

    public Long getCloudId() {
        return cloudId;
    }

    public String getUserName() {
        return userName;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
        this.updatedAt = Instant.now();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

