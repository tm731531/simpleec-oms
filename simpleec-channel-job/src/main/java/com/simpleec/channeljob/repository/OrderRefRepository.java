package com.simpleec.channeljob.repository;

import com.simpleec.channeljob.entity.OrderRef;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface OrderRefRepository extends JpaRepository<OrderRef, String> {
}
