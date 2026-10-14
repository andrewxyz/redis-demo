package com.example.redisdemo.repository;

import com.example.redisdemo.model.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA repository — gives us all the basic
 * CRUD methods (findById, save, delete, etc.) for free.
 */
@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {

    // Custom query: find all products in a given category
    List<Product> findByCategory(String category);
}
