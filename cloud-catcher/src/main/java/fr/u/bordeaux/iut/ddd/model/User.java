package fr.u.bordeaux.iut.ddd.model;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "app_user")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_name", nullable = false, unique = true)
    private String userName;

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Cloud> clouds = new ArrayList<>();

    protected User() {
        // JPA
    }

    public User(SecurityIdentity identity) {
        this.userName = identity.getPrincipal().getName();
    }

    public User(String userName) {
        this.userName = userName;
    }

    public Long getId() {
        return id;
    }

    public String getUserName() {
        return userName;
    }

    public List<Cloud> getClouds() {
        return clouds;
    }

    public void addCloud(Cloud cloud) {
        this.clouds.add(cloud);
    }
}
