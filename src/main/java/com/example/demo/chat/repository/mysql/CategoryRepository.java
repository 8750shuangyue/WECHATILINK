package com.example.demo.chat.repository.mysql;

import com.example.demo.chat.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CategoryRepository extends JpaRepository<Category, Long> {

    List<Category> findByParentCategoryId(Long parentCategoryId);

    List<Category> findAllByOrderByParentCategoryIdAscIdAsc();

    List<Category> findByNameContaining(String name);
}