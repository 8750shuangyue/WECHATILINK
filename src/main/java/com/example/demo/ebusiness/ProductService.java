package com.example.demo.ebusiness;

import com.example.demo.chat.entity.Product;
import com.example.demo.chat.repository.mysql.ProductRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ProductService {

    @Autowired
    private ProductRepository productRepository;

    public Product save(Product product) {
        if (product.getUserId() == null) {
            product.setUserId(1L);
        }
        if (product.getStatus() == null || product.getStatus().isEmpty()) {
            product.setStatus("ON");
        }
        if (product.getPrice() == null) {
            product.setPrice(0.00);
        }
        if (product.getStock() == null) {
            product.setStock(0);
        }
        return productRepository.save(product);
    }

    public Map<String, Object> pageQuery(int pageNum, int pageSize) {
        int page = Math.max(0, pageNum - 1);
        int size = Math.max(1, pageSize);
        
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Product> productPage = productRepository.findAll(pageable);
        
        Map<String, Object> result = new HashMap<>();
        result.put("pageNum", pageNum);
        result.put("pageSize", size);
        result.put("totalCount", productPage.getTotalElements());
        result.put("totalPage", productPage.getTotalPages());
        result.put("data", productPage.getContent());
        
        return result;
    }

    public Product findById(Long id) {
        return productRepository.findById(id).orElse(null);
    }

    public void deleteById(Long id) {
        productRepository.deleteById(id);
    }

    public List<Product> findAll() {
        return productRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    public long countByStatus(String status) {
        return productRepository.countByStatus(status);
    }
}