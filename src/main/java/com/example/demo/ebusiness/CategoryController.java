package com.example.demo.ebusiness;

import com.example.demo.aicare.Result;
import com.example.demo.chat.entity.Category;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/shop/category")
public class CategoryController {

    @Autowired
    private CategoryService categoryService;

    @GetMapping("/list")
    public Result<List<Category>> list() {
        return Result.success(categoryService.findAll());
    }

    @PostMapping("/add")
    public Result<Category> add(@RequestBody Category category) {
        if (category.getName() == null || category.getName().trim().isEmpty()) {
            return Result.error("分类名称不能为空");
        }
        Category saved = categoryService.save(category);
        return Result.success(saved);
    }

    @GetMapping("/init")
    public Result<Void> init() {
        categoryService.initDefaultCategories();
        return Result.success(null);
    }
}