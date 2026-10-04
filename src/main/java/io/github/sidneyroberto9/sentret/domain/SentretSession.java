package io.github.sidneyroberto9.sentret.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
        name = "spring_session_lite_sessions",
        indexes = {
                @Index(name = "idx_spring_session_lite_sessions_session_id", columnList = "session_id", unique = true),
                @Index(name = "idx_spring_session_lite_sessions_user_id", columnList = "user_id"),
                @Index(name = "idx_spring_session_lite_sessions_expires_at", columnList = "expires_at")
        }
)
public class SentretSession {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "session_id", nullable = false, unique = true, length = 32)
    private String sessionId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "email")
    private String email;

    @Column(name = "roles")
    private String roles;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "last_accessed_at")
    private Instant lastAccessedAt;
}
