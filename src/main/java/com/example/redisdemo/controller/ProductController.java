package com.example.redisdemo.controller;

import com.example.redisdemo.model.Product;
import com.example.redisdemo.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller — exposes the caching demo as HTTP endpoints.
 *
 * Quick-test URLs (run with curl or open in browser):
 *
 *   GET  /api/products          → all products  (cached as myapp:products:all)
 *   GET  /api/products/1        → one product   (cached as myapp:products:1)
 *   GET  /api/products/category/Laptops → by category (cached as myapp:categories:Laptops)
 *   POST /api/products          → create        (saves + updates cache)
 *   PUT  /api/products/1        → update        (saves + refreshes cache)
 *   DELETE /api/products/1      → delete        (removes from cache + DB)
 */
@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @GetMapping
    public ResponseEntity<List<Product>> getAll() {
        return ResponseEntity.ok(productService.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Product> getById(@PathVariable Long id) {
        return ResponseEntity.ok(productService.findById(id));
    }

    @GetMapping("/category/{category}")
    public ResponseEntity<List<Product>> getByCategory(@PathVariable String category) {
        return ResponseEntity.ok(productService.findByCategory(category));
    }

    @PostMapping
    public ResponseEntity<Product> create(@RequestBody Product product) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(productService.createProduct(product));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Product> update(@PathVariable Long id, @RequestBody Product product) {
        product.setId(id);
        return ResponseEntity.ok(productService.updateProduct(product));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        productService.deleteProduct(id);
        return ResponseEntity.noContent().build();
    }
}
