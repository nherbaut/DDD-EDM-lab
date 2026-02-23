package fr.u.bordeaux.iut.ddd.conf;

import fr.u.bordeaux.iut.ddd.model.UserQuotaProjection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;

@ApplicationScoped
public class QuotaProjectionService {

    @PersistenceContext
    EntityManager entityManager;

    @Transactional
    public void upsert(String userName, int remainingQuota) {
        if (userName == null || userName.isBlank()) {
            return;
        }
        UserQuotaProjection existing = entityManager.find(UserQuotaProjection.class, userName);
        if (existing == null) {
            entityManager.persist(new UserQuotaProjection(userName, remainingQuota));
            return;
        }
        existing.setRemainingQuota(remainingQuota);
    }

    public Integer getQuota(String userName) {
        if (userName == null || userName.isBlank()) {
            return null;
        }
        UserQuotaProjection projection = entityManager.find(UserQuotaProjection.class, userName);
        return projection == null ? null : projection.getRemainingQuota();
    }
}

