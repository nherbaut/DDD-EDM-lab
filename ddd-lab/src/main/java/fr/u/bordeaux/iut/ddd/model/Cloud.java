package fr.u.bordeaux.iut.ddd.model;

import jakarta.persistence.Column;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "cloud")
public class Cloud {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "minio_object_name", nullable = false)
    private String minioObjectName;

    @Column(name = "minio_etag", nullable = false)
    private String minioETag;

    @Column(name = "original_file_name", nullable = false)
    private String originalFileName;

    @Column(name = "cloud_name")
    private String cloudName;

    @Column(name = "prevent_further_processing", nullable = false)
    private boolean preventFurtherProcessing;

    @Column(name = "deletion_requested", nullable = false)
    private boolean deletionRequested;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @OneToOne(mappedBy = "cloud", orphanRemoval = true, cascade = CascadeType.ALL)
    private GeneralClassificationDecision generalClassificationDecision;

    protected Cloud() {
        // JPA
    }

    public Cloud(String minioObjectName, String minioETag, User user, String originalFileName) {
        this.minioObjectName = minioObjectName;
        this.minioETag = minioETag;
        this.user = user;
        this.originalFileName = originalFileName;
        this.preventFurtherProcessing = false;
        this.deletionRequested = false;
    }

    public Long getId() {
        return id;
    }

    public String getMinioObjectName() {
        return minioObjectName;
    }

    public void setMinioObjectName(String minioObjectName) {
        this.minioObjectName = minioObjectName;
    }

    public String getMinioETag() {
        return minioETag;
    }

    public void setMinioETag(String minioETag) {
        this.minioETag = minioETag;
    }

    public String getOriginalFileName() {
        return originalFileName;
    }

    public void setOriginalFileName(String originalFileName) {
        this.originalFileName = originalFileName;
    }

    public User getUser() {
        return user;
    }

    public String getCloudName() {
        return cloudName;
    }

    public void setCloudName(String cloudName) {
        this.cloudName = cloudName;
    }

    public boolean isPreventFurtherProcessing() {
        return preventFurtherProcessing;
    }

    public void setPreventFurtherProcessing(boolean preventFurtherProcessing) {
        this.preventFurtherProcessing = preventFurtherProcessing;
    }

    public boolean isDeletionRequested() {
        return deletionRequested;
    }

    public void setDeletionRequested(boolean deletionRequested) {
        this.deletionRequested = deletionRequested;
    }

    public GeneralClassificationDecision getGeneralClassificationDecision() {
        return generalClassificationDecision;
    }

    public void setGeneralClassificationDecision(GeneralClassificationDecision generalClassificationDecision) {
        this.generalClassificationDecision = generalClassificationDecision;
        if (generalClassificationDecision != null && generalClassificationDecision.getCloud() != this) {
            generalClassificationDecision.setCloud(this);
        }
    }

}
