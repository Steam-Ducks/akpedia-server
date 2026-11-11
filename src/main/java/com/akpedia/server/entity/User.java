package com.akpedia.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * System user. The sector indirectly determines their permissions per category.
 *
 * <p>The account itself, password included, lives in Keycloak; this row mirrors it, linked by
 * {@link #getKeycloakId()}, so documents and approvals can point at a user.
 */
@Entity
@Table(name = "users")
public class User {

    /** The name a deleted user's row is left with. */
    public static final String DELETED_NAME = "Usuário removido";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "email", nullable = false, length = 255, unique = true)
    private String email;

    @Column(name = "keycloak_id", length = 36, unique = true)
    private String keycloakId;

    @Column(name = "cpf", length = 11, unique = true)
    private String cpf;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sector_id", nullable = false)
    private Sector sector;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    protected User() {
    }

    public User(String name, String email, Sector sector) {
        this.name = name;
        this.email = email;
        this.sector = sector;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    /** Id of the Keycloak account (the token's {@code sub}); {@code null} for a user never logged in. */
    public String getKeycloakId() {
        return keycloakId;
    }

    public void setKeycloakId(String keycloakId) {
        this.keycloakId = keycloakId;
    }

    /** The CPF's 11 digits, as on the Keycloak account; {@code null} until the user's first access. */
    public String getCpf() {
        return cpf;
    }

    public void setCpf(String cpf) {
        this.cpf = cpf;
    }

    public Sector getSector() {
        return sector;
    }

    public void setSector(Sector sector) {
        this.sector = sector;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    /** When the user deleted their account; {@code null} while it exists. */
    public OffsetDateTime getDeletedAt() {
        return deletedAt;
    }

    /**
     * What is left once the user deleted their account: no name, email, CPF or Keycloak link. The row
     * stays, since documents and terms acceptances point at it.
     */
    public void anonymize(OffsetDateTime when) {
        this.name = DELETED_NAME;
        this.email = "removido-%d@removido.invalid".formatted(id);
        this.keycloakId = null;
        this.cpf = null;
        this.active = false;
        if (deletedAt == null) {
            this.deletedAt = when;
        }
    }
}
