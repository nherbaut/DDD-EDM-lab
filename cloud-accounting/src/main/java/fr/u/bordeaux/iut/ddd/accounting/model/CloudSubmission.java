package fr.u.bordeaux.iut.ddd.accounting.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "cloud_submission")
public class CloudSubmission {

    @Id
    private Long cloudId;

    @Column(nullable = false, length = 128)
    private String userName;

    public CloudSubmission() {
    }

    public CloudSubmission(Long cloudId, String userName) {
        this.cloudId = cloudId;
        this.userName = userName;
    }

    public Long getCloudId() {
        return cloudId;
    }

    public void setCloudId(Long cloudId) {
        this.cloudId = cloudId;
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }
}
