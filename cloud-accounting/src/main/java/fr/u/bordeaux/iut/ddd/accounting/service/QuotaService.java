package fr.u.bordeaux.iut.ddd.accounting.service;

import fr.u.bordeaux.iut.ddd.accounting.model.AccountingUser;
import fr.u.bordeaux.iut.ddd.accounting.model.CloudSubmission;
import fr.u.bordeaux.iut.ddd.accounting.model.QuotaReservation;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;

@ApplicationScoped
public class QuotaService {

    private static final int WELCOME_QUOTA = 10;
    private static final int[] ALLOWED_PACKAGES = {1, 10, 30};

    public enum ReservationStatus {
        RESERVED,
        REJECTED
    }

    public record ReservationDecision(
            ReservationStatus status,
            String requestId,
            long cloudId,
            String userName,
            int remainingQuota
    ) {
        public boolean reserved() {
            return status == ReservationStatus.RESERVED;
        }
    }

    @PersistenceContext
    EntityManager entityManager;

    @Transactional
    public AccountingUser buyPackage(String userName) {
        return buyPackage(userName, 10);
    }

    @Transactional
    public AccountingUser buyPackage(String userName, int quotaAmount) {
        int normalizedQuota = normalizedPackageQuota(quotaAmount);
        AccountingUser user = findOrCreateUser(userName);
        user.setQuota(user.getQuota() + normalizedQuota);
        return user;
    }

    public int normalizedPackageQuota(int quotaAmount) {
        return normalizedPackageQuotaInternal(quotaAmount);
    }

    @Transactional
    public void recordSubmission(long cloudId, String userName) {
        CloudSubmission existing = entityManager.find(CloudSubmission.class, cloudId);
        if (existing == null) {
            entityManager.persist(new CloudSubmission(cloudId, userName));
            return;
        }
        existing.setUserName(userName);
    }

    @Transactional
    public ReservationDecision reserveQuotaForRequest(String requestId, long cloudId, String userName) {
        QuotaReservation existing = entityManager.find(QuotaReservation.class, requestId);
        if (existing != null) {
            AccountingUser existingUser = findOrCreateUser(existing.getUserName());
            ReservationStatus status = "RESERVED".equals(existing.getStatus())
                    ? ReservationStatus.RESERVED
                    : ReservationStatus.REJECTED;
            return new ReservationDecision(
                    status,
                    existing.getRequestId(),
                    existing.getCloudId(),
                    existing.getUserName(),
                    existingUser.getQuota()
            );
        }

        recordSubmission(cloudId, userName);
        AccountingUser user = findOrCreateUser(userName);
        if (user.getQuota() > 0) {
            user.setQuota(user.getQuota() - 1);
            entityManager.persist(new QuotaReservation(requestId, cloudId, userName, "RESERVED"));
            return new ReservationDecision(ReservationStatus.RESERVED, requestId, cloudId, userName, user.getQuota());
        }
        entityManager.persist(new QuotaReservation(requestId, cloudId, userName, "REJECTED"));
        return new ReservationDecision(ReservationStatus.REJECTED, requestId, cloudId, userName, user.getQuota());
    }

    @Transactional
    public void markReservationCompleted(String requestId, long cloudId, String userName) {
        if (requestId == null || requestId.isBlank()) {
            return;
        }
        QuotaReservation reservation = entityManager.find(QuotaReservation.class, requestId);
        if (reservation == null) {
            if (userName == null || userName.isBlank()) {
                return;
            }
            entityManager.persist(new QuotaReservation(requestId, cloudId, userName, "COMPLETED"));
            return;
        }
        if (!"COMPLETED".equals(reservation.getStatus())) {
            reservation.setStatus("COMPLETED");
        }
    }

    @Transactional
    public AccountingUser getOrCreate(String userName) {
        return findOrCreateUser(userName);
    }

    @Transactional
    public AccountingUser allocateWelcomeQuotaIfNew(String userName) {
        try {
            return entityManager.createQuery(
                            "select u from AccountingUser u where u.userName = :userName", AccountingUser.class)
                    .setParameter("userName", userName)
                    .getSingleResult();
        } catch (NoResultException ignored) {
            AccountingUser created = new AccountingUser(userName, WELCOME_QUOTA);
            entityManager.persist(created);
            return created;
        }
    }

    private AccountingUser findOrCreateUser(String userName) {
        try {
            return entityManager.createQuery(
                            "select u from AccountingUser u where u.userName = :userName", AccountingUser.class)
                    .setParameter("userName", userName)
                    .getSingleResult();
        } catch (NoResultException ignored) {
            AccountingUser created = new AccountingUser(userName, 0);
            entityManager.persist(created);
            return created;
        }
    }

    private int normalizedPackageQuotaInternal(int quotaAmount) {
        for (int allowed : ALLOWED_PACKAGES) {
            if (allowed == quotaAmount) {
                return quotaAmount;
            }
        }
        return 10;
    }
}
