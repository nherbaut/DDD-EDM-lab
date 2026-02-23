package fr.u.bordeaux.iut.ddd.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "quota_purchase_projection")
public class QuotaPurchaseProjection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String userName;

    @Column(nullable = false)
    private int purchasedQuota;

    @Column(nullable = false)
    private int amountCents;

    @Column(nullable = false)
    private Instant purchasedAt;

    protected QuotaPurchaseProjection() {
        // JPA
    }

    public QuotaPurchaseProjection(String userName, int purchasedQuota, int amountCents, Instant purchasedAt) {
        this.userName = userName;
        this.purchasedQuota = purchasedQuota;
        this.amountCents = amountCents;
        this.purchasedAt = purchasedAt;
    }

    public Long getId() {
        return id;
    }

    public String getUserName() {
        return userName;
    }

    public int getPurchasedQuota() {
        return purchasedQuota;
    }

    public int getAmountCents() {
        return amountCents;
    }

    public Instant getPurchasedAt() {
        return purchasedAt;
    }
}
