package com.example.zavelo.user;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface UserRepository extends JpaRepository<AppUser, Long> {
    Optional<AppUser> findByUsername(String username);
    Optional<AppUser> findByConnectKey(String connectKey);
    boolean existsByUsername(String username);
    boolean existsByConnectKey(String connectKey);
}
