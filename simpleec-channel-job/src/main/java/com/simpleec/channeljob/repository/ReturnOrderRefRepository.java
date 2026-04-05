package com.simpleec.channeljob.repository;

import com.simpleec.channeljob.entity.ReturnOrderRef;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ReturnOrderRefRepository extends JpaRepository<ReturnOrderRef, String> {
}
