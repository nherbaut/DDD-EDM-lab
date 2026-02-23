package fr.u.bordeaux.iut.ddd.conf;

import fr.u.bordeaux.iut.ddd.model.QuotaPurchaseProjection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class AccountantProjectionService {

    @PersistenceContext
    EntityManager entityManager;

    @Transactional
    public void recordPurchase(String userName, int purchasedQuota, int amountCents, Instant purchasedAt) {
        if (userName == null || userName.isBlank() || purchasedQuota <= 0 || amountCents < 0) {
            return;
        }
        Instant at = purchasedAt == null ? Instant.now() : purchasedAt;
        entityManager.persist(new QuotaPurchaseProjection(userName, purchasedQuota, amountCents, at));
    }

    public Map<String, Object> summary() {
        Long totalQuotaBought = entityManager.createQuery(
                        "select coalesce(sum(p.purchasedQuota), 0) from QuotaPurchaseProjection p", Long.class)
                .getSingleResult();
        Long totalAmountCents = entityManager.createQuery(
                        "select coalesce(sum(p.amountCents), 0) from QuotaPurchaseProjection p", Long.class)
                .getSingleResult();
        List<QuotaPurchaseProjection> purchases = entityManager.createQuery(
                        "select p from QuotaPurchaseProjection p order by p.purchasedAt desc, p.id desc",
                        QuotaPurchaseProjection.class)
                .getResultList();

        List<Map<String, Object>> transactions = purchases.stream().map(p -> {
            Map<String, Object> tx = new HashMap<>();
            tx.put("id", p.getId());
            tx.put("userName", p.getUserName());
            tx.put("purchasedQuota", p.getPurchasedQuota());
            tx.put("amountCents", p.getAmountCents());
            tx.put("purchasedAt", p.getPurchasedAt());
            return tx;
        }).toList();

        Map<String, Object> summary = new HashMap<>();
        summary.put("totalQuotaBought", totalQuotaBought == null ? 0L : totalQuotaBought);
        summary.put("totalAmountCents", totalAmountCents == null ? 0L : totalAmountCents);
        summary.put("transactions", transactions);
        return summary;
    }
}
