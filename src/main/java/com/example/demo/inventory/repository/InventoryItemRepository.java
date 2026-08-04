package com.example.demo.inventory.repository;

import com.example.demo.inventory.entity.InventoryItem;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface InventoryItemRepository extends JpaRepository<InventoryItem, Long> {
    List<InventoryItem> findByUserIdOrderByCreatedAtDesc(String userId);
    List<InventoryItem> findByUserIdAndCategory(String userId, InventoryItem.ItemCategory category);
}
