package com.example.redisdemo;

import com.example.redisdemo.model.Product;
import com.example.redisdemo.repository.ProductRepository;
import com.example.redisdemo.service.ProductService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cache.CacheManager;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests that verify caching behaviour without a real Redis server.
 * Uses Spring's simple in-memory CacheManager for isolation.
 */
@SpringBootTest(properties = {
    // Override to use simple in-memory cache for tests (no Redis needed)
    "spring.cache.type=simple",
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration," +
    "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
})
class ProductServiceTest {

    @Autowired
    private ProductService productService;

    @MockBean
    private ProductRepository productRepository;

    @Autowired
    private CacheManager cacheManager;

    @Test
    void findById_shouldCacheResult_onSecondCall() {
        Product product = new Product(1L, "MacBook Pro", 2499.99, "Laptops");
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));

        // First call — hits the database
        Product first = productService.findById(1L);

        // Second call — should come from cache, NOT the database
        Product second = productService.findById(1L);

        assertThat(first.getName()).isEqualTo("MacBook Pro");
        assertThat(second.getName()).isEqualTo("MacBook Pro");

        // Repository should only have been called ONCE despite two service calls
        verify(productRepository, times(1)).findById(1L);
    }

    @Test
    void deleteProduct_shouldEvictFromCache() {
        Product product = new Product(2L, "iPhone 15", 999.99, "Phones");
        when(productRepository.findById(2L)).thenReturn(Optional.of(product));
        doNothing().when(productRepository).deleteById(2L);

        // Populate the cache
        productService.findById(2L);

        // Delete should evict from cache
        productService.deleteProduct(2L);

        // After eviction, next findById should hit the DB again
        productService.findById(2L);

        // DB called twice: once before delete, once after eviction
        verify(productRepository, times(2)).findById(2L);
    }
}
