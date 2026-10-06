package com.example.demo.inventory;

import com.example.demo.inventory.entity.InventoryItem;
import com.example.demo.inventory.repository.InventoryItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);
    private final InventoryItemRepository repository;

    public InventoryService(InventoryItemRepository repository) {
        this.repository = repository;
    }

    public InventoryItem addItem(String userId, String name, String category, Double quantity,
                                  String unit, String openDate, Double dailyConsumption,
                                  Double lowStockThreshold, String notes, Long linkedProductId) {
        InventoryItem item = InventoryItem.builder()
                .userId(userId)
                .name(name)
                .category(InventoryItem.ItemCategory.valueOf(category))
                .quantity(quantity)
                .unit(unit)
                .dailyConsumption(dailyConsumption)
                .lowStockThreshold(lowStockThreshold != null ? lowStockThreshold : 0.0)
                .notes(notes)
                .linkedProductId(linkedProductId)
                .build();
        if (openDate != null && !openDate.isBlank()) {
            item.setOpenDate(LocalDate.parse(openDate));
        }
        return repository.save(item);
    }

    public InventoryItem updateItem(Long id, String userId, String name, String category,
                                     Double quantity, String unit, String openDate,
                                     Double dailyConsumption, Double lowStockThreshold,
                                     String notes, Long linkedProductId) {
        InventoryItem item = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("物品不存在"));
        if (!item.getUserId().equals(userId)) {
            throw new RuntimeException("无权修改");
        }
        if (name != null) item.setName(name);
        if (category != null) item.setCategory(InventoryItem.ItemCategory.valueOf(category));
        if (quantity != null) item.setQuantity(quantity);
        if (unit != null) item.setUnit(unit);
        if (openDate != null && !openDate.isBlank()) item.setOpenDate(LocalDate.parse(openDate));
        if (dailyConsumption != null) item.setDailyConsumption(dailyConsumption);
        if (lowStockThreshold != null) item.setLowStockThreshold(lowStockThreshold);
        if (notes != null) item.setNotes(notes);
        if (linkedProductId != null) item.setLinkedProductId(linkedProductId);
        return repository.save(item);
    }

    public void deleteItem(Long id, String userId) {
        InventoryItem item = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("物品不存在"));
        if (!item.getUserId().equals(userId)) {
            throw new RuntimeException("无权删除");
        }
        repository.deleteById(id);
    }

    public List<InventoryItem> getUserItems(String userId) {
        return repository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    public InventoryItem consumeItem(Long id, String userId, Double amount) {
        InventoryItem item = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("物品不存在"));
        if (!item.getUserId().equals(userId)) {
            throw new RuntimeException("无权操作");
        }
        double newQuantity = item.getQuantity() - amount;
        if (newQuantity < 0) newQuantity = 0;
        item.setQuantity(newQuantity);

        if (amount > 0 && item.getDailyConsumption() == null) {
            if (item.getOpenDate() != null) {
                long daysOpen = ChronoUnit.DAYS.between(item.getOpenDate(), LocalDate.now());
                if (daysOpen > 0) {
                    item.setDailyConsumption(Math.round(amount * 100.0) / 100.0);
                }
            }
        }

        return repository.save(item);
    }

    public List<Map<String, Object>> getLowStockAlerts(String userId) {
        List<InventoryItem> items = repository.findByUserIdOrderByCreatedAtDesc(userId);
        List<Map<String, Object>> alerts = new ArrayList<>();
        for (InventoryItem item : items) {
            boolean isLow = item.getQuantity() <= item.getLowStockThreshold();
            Integer daysRemaining = null;
            if (item.getDailyConsumption() != null && item.getDailyConsumption() > 0) {
                daysRemaining = (int) Math.floor(item.getQuantity() / item.getDailyConsumption());
                if (daysRemaining <= 3) isLow = true;
            }
            if (isLow) {
                Map<String, Object> alert = new HashMap<>();
                alert.put("id", item.getId());
                alert.put("name", item.getName());
                alert.put("category", item.getCategory().name());
                alert.put("quantity", item.getQuantity());
                alert.put("unit", item.getUnit());
                alert.put("threshold", item.getLowStockThreshold());
                alert.put("daysRemaining", daysRemaining);
                alert.put("linkedProductId", item.getLinkedProductId());
                alerts.add(alert);
            }
        }
        return alerts;
    }

    @Scheduled(fixedDelay = 21600000, initialDelay = 60000)
    public void checkLowStock() {
        try {
            List<InventoryItem> allItems = repository.findAll();
            Set<String> alertedUsers = new HashSet<>();
            for (InventoryItem item : allItems) {
                if (alertedUsers.contains(item.getUserId())) continue;
                boolean isLow = item.getQuantity() <= item.getLowStockThreshold();
                if (item.getDailyConsumption() != null && item.getDailyConsumption() > 0) {
                    int daysRemaining = (int) Math.floor(item.getQuantity() / item.getDailyConsumption());
                    if (daysRemaining <= 3 && daysRemaining >= 0) isLow = true;
                }
                if (isLow) {
                    alertedUsers.add(item.getUserId());
                    log.info("[Inventory] Low stock alert for user {}: {}", item.getUserId(), item.getName());
                }
            }
        } catch (Exception e) {
            log.error("[Inventory] Error checking low stock: {}", e.getMessage(), e);
        }
    }

    public String getCategoryLabel(InventoryItem.ItemCategory category) {
        return switch (category) {
            case PET_FOOD -> "宠物食品";
            case CAT_LITTER -> "猫砂";
            case MEDICINE -> "药品";
            case SUPPLEMENT -> "营养品";
            case FERTILIZER -> "肥料";
            case SOIL -> "土壤";
            case PESTICIDE -> "农药";
            case TREAT -> "零食";
            case TOY -> "玩具";
            case OTHER -> "其他";
        };
    }
}
