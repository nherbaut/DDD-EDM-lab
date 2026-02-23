package fr.u.bordeaux.iut.ddd.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(
        name = "general_classification_decision",
        uniqueConstraints = @UniqueConstraint(name = "uk_general_decision_cloud", columnNames = {"cloud_id"})
)
public class GeneralClassificationDecision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cloud_id", nullable = false, unique = true)
    private Cloud cloud;

    @Column(name = "label", nullable = false)
    private String label;

    @Column(name = "score", nullable = false)
    private double score;

    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;

    protected GeneralClassificationDecision() {
        // JPA
    }

    public GeneralClassificationDecision(Cloud cloud, String label, double score) {
        this.cloud = cloud;
        this.label = label;
        this.score = score;
        this.detectedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Cloud getCloud() {
        return cloud;
    }

    public void setCloud(Cloud cloud) {
        this.cloud = cloud;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public double getScore() {
        return score;
    }

    public void setScore(double score) {
        this.score = score;
    }

    public Instant getDetectedAt() {
        return detectedAt;
    }

    public void setDetectedAt(Instant detectedAt) {
        this.detectedAt = detectedAt;
    }
}
