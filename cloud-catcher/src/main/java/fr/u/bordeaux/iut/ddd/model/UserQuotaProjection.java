package fr.u.bordeaux.iut.ddd.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "user_quota_projection")
public class UserQuotaProjection {

    @Id
    @Column(name = "user_name", nullable = false, length = 128)
    private String userName;

    @Column(name = "remaining_quota", nullable = false)
    private int remainingQuota;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UserQuotaProjection() {
    }

    public UserQuotaProjection(String userName, int remainingQuota) {
        this.userName = userName;
        this.remainingQuota = remainingQuota;
        this.updatedAt = Instant.now();
    }

    public String getUserName() {
        return userName;
    }

    public int getRemainingQuota() {
        return remainingQuota;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setRemainingQuota(int remainingQuota) {
        this.remainingQuota = remainingQuota;
        this.updatedAt = Instant.now();
    }
}

