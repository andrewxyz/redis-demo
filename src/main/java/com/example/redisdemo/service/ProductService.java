package com.example.redisdemo.service;

import com.example.redisdemo.model.Product;
import com.example.redisdemo.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * ProductService demonstrates the 4 Redis patterns in real usage.
 *
 * Watch the console logs when you call these methods:
 *   First call  → "Fetching product from DATABASE" (cache miss)
 *   Second call → nothing logged           (cache hit, DB not touched)
 */
@Service
@RequiredArgsConstructor  // Lombok: injects final fields via constructor
@Slf4j                    // Lombok: gives us the log.debug(...) shorthand
public class ProductService {

    private final ProductRepository productRepository;

    // ─────────────────────────────────────────────────────────────
    // @Cacheable: cache the result on first call.
    //   value = which cache to use  (matches a key in RedisConfig)
    //   key   = what to use as the Redis key suffix
    //
    // Redis key produced:  myapp:products:1
    //   ↑ prefix from Pattern 3   ↑ id from #id
    // ─────────────────────────────────────────────────────────────

    @Cacheable(value = "products", key = "#id")
    public Product findById(Long id) {
        log.debug("🗄️  Cache MISS — fetching product {} from DATABASE", id);
        return productRepository.findById(id)
            .orElseThrow(() -> new RuntimeException("Product not found: " + id));
    }

    /**
     * Caches the full product list under a fixed key "all".
     * Redis key: myapp:products:all
     */
    @Cacheable(value = "products", key = "'all'")
    public List<Product> findAll() {
        log.debug("🗄️  Cache MISS — fetching ALL products from DATABASE");
        return productRepository.findAll();
    }

    /**
     * Caches products by category.
     * Redis key: myapp:categories:Laptops
     */
    @Cacheable(value = "categories", key = "#category")
    public List<Product> findByCategory(String category) {
        log.debug("🗄️  Cache MISS — fetching category '{}' from DATABASE", category);
        return productRepository.findByCategory(category);
    }

    // ─────────────────────────────────────────────────────────────
    // @CachePut: always runs the method AND updates the cache.
    //   Use on create/update so the cache stays in sync with the DB.
    //
    // @CacheEvict(allEntries=true): wipes the "all" list cache so
    //   it gets rebuilt on the next request (stale list = bad UX).
    // ─────────────────────────────────────────────────────────────

    @Caching(
        put    = { @CachePut(value = "products", key = "#result.id") },
        evict  = { @CacheEvict(value = "products", key = "'all'"),
                   @CacheEvict(value = "categories", allEntries = true) }
            // allEntries = true is the pragmatic solution: wipe all category entries at once,
            // let them rebuild on next request.
            // The rule of thumb: use allEntries = true only when you can't know which specific key
            // to target at the time of the operation.
    )
    public Product createProduct(Product product) {
        log.debug("💾  Saving NEW product and updating cache");
        return productRepository.save(product);
    }

    @Caching(
        put    = { @CachePut(value = "products", key = "#product.id") },
        evict  = { @CacheEvict(value = "products", key = "'all'"),
                   @CacheEvict(value = "categories", allEntries = true) }
    )
    public Product updateProduct(Product product) {
        log.debug("💾  Updating product {} and refreshing cache", product.getId());
        if (!productRepository.existsById(product.getId())) {
            throw new RuntimeException("Product not found: " + product.getId());
        }
        return productRepository.save(product);
    }

    // ─────────────────────────────────────────────────────────────
    // @CacheEvict: removes the entry from Redis on delete.
    //   allEntries = true on the list caches wipes every entry
    //   so no stale lists are served after a deletion.
    // ─────────────────────────────────────────────────────────────

    @Caching(evict = {
        @CacheEvict(value = "products",   key = "#id"),
        @CacheEvict(value = "products",   key = "'all'"),
        @CacheEvict(value = "categories", allEntries = true)
    })
    public void deleteProduct(Long id) {
        log.debug("🗑️  Deleting product {} and evicting from cache", id);
        productRepository.deleteById(id);
    }
}
