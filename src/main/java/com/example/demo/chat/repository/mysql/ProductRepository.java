package com.example.demo.chat.repository.mysql;

import com.example.demo.chat.entity.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {

    Page<Product> findByStatus(String status, Pageable pageable);

    Page<Product> findByStatusContaining(String status, Pageable pageable);

    List<Product> findByNameContaining(String name);

    long countByStatus(String status);
}