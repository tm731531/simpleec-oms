package com.simpleec.core.repository;

import com.simpleec.core.entity.PlatformAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PlatformAccountRepository extends JpaRepository<PlatformAccount, String> {
    Optional<PlatformAccount> findByEmail(String email);
}
