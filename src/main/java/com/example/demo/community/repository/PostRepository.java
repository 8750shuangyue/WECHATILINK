package com.example.demo.community.repository;

import com.example.demo.community.entity.Post;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PostRepository extends JpaRepository<Post, Long> {

    Page<Post> findByCategoryOrderByCreatedAtDesc(String category, Pageable pageable);

    Page<Post> findByTagsContaining(String tag, Pageable pageable);

    Page<Post> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);

    long countByTagsContaining(String tag);
}
