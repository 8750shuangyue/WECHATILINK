package com.example.demo.ebusiness;

import com.example.demo.aicare.Result;
import com.example.demo.chat.entity.Product;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/shop")
public class ProductController {

    @Autowired
    private ProductService productService;

    @PostMapping("/publish")
    public Result<Product> publish(@RequestBody Product product) {
        if (product.getName() == null || product.getName().trim().isEmpty()) {
            return Result.error("商品名称不能为空");
        }
        if (product.getPrice() == null || product.getPrice() < 0) {
            return Result.error("商品价格不能为空且不能小于0");
        }
        if (product.getStock() == null || product.getStock() < 0) {
            return Result.error("库存不能为空且不能小于0");
        }
        Product saved = productService.save(product);
        return Result.success(saved);
    }

    @GetMapping("/list")
    public Result<Map<String, Object>> list(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "12") int pageSize) {
        Map<String, Object> data = productService.pageQuery(pageNum, pageSize);
        return Result.success(data);
    }

    @GetMapping("/all")
    public Result<?> all() {
        return Result.success(productService.findAll());
    }

    @GetMapping("/detail/{id}")
    public Result<Product> detail(@PathVariable Long id) {
        Product product = productService.findById(id);
        if (product == null) {
            return Result.error("商品不存在");
        }
        return Result.success(product);
    }

    @DeleteMapping("/delete/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        productService.deleteById(id);
        return Result.success(null);
    }

    @GetMapping("/stats")
    public Result<Map<String, Object>> stats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("total", productService.findAll().size());
        stats.put("onSale", productService.countByStatus("ON"));
        stats.put("offSale", productService.countByStatus("OFF"));
        return Result.success(stats);
    }
}