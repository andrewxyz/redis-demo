package com.example.redisdemo.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Product entity stored in H2 (our "real" database).
 *
 * Implements Serializable — required so Spring can store
 * this object in Redis as JSON.
 */
@Entity
@Table(name = "products")
@Data                   // Lombok: generates getters, setters, toString, equals, hashCode
@NoArgsConstructor      // Lombok: generates no-arg constructor (required by JPA)
@AllArgsConstructor     // Lombok: generates all-arg constructor (handy for seeding)
public class Product implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private Double price;
    private String category;
}
