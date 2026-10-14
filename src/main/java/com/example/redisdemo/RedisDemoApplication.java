package com.example.redisdemo;

import com.example.redisdemo.model.Product;
import com.example.redisdemo.repository.ProductRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;

/**
 * Entry point.
 *
 * @EnableCaching activates Spring's cache proxy — without this
 * none of the @Cacheable / @CacheEvict annotations do anything.
 */
@SpringBootApplication
@EnableCaching
public class RedisDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(RedisDemoApplication.class, args);
    }

    /**
     * Seeds the H2 database with a few products on startup
     * so the demo endpoints have real data to return.
     */
    @Bean
    CommandLineRunner seedData(ProductRepository repo) {
        return args -> {
            repo.save(new Product(null, "MacBook Pro",      2499.99, "Laptops"));
            repo.save(new Product(null, "iPhone 15",        999.99,  "Phones"));
            repo.save(new Product(null, "AirPods Pro",      249.99,  "Audio"));
            repo.save(new Product(null, "iPad Air",         749.99,  "Tablets"));
            repo.save(new Product(null, "Apple Watch",      399.99,  "Wearables"));
            System.out.println("✅  5 products seeded into H2 database");
        };
    }
}
