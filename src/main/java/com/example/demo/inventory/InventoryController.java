package com.example.demo.inventory;

import com.example.demo.aicare.Result;
import com.example.demo.inventory.entity.InventoryItem;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/inventory")
public class InventoryController {

    private static final Logger logger = LoggerFactory.getLogger(InventoryController.class);
    private final InventoryService inventoryService;

    public InventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @PostMapping("/items")
    public Result<InventoryItem> addItem(@RequestBody Map<String, Object> params, HttpSession session) {
        String userId = (String) session.getAttribute("user");
        if (userId == null) return Result.error("未登录");

        try {
            InventoryItem item = inventoryService.addItem(
                    userId,
                    (String) params.get("name"),
                    (String) params.get("category"),
                    ((Number) params.get("quantity")).doubleValue(),
                    (String) params.get("unit"),
                    (String) params.get("openDate"),
                    params.get("dailyConsumption") != null ? ((Number) params.get("dailyConsumption")).doubleValue() : null,
                    params.get("lowStockThreshold") != null ? ((Number) params.get("lowStockThreshold")).doubleValue() : null,
                    (String) params.get("notes"),
                    params.get("linkedProductId") != null ? ((Number) params.get("linkedProductId")).longValue() : null
            );
            return Result.success(item);
        } catch (Exception e) {
            logger.error("Add inventory item failed", e);
            return Result.error("添加失败：" + e.getMessage());
        }
    }

    @GetMapping("/items")
    public Result<List<InventoryItem>> getItems(HttpSession session) {
        String userId = (String) session.getAttribute("user");
        if (userId == null) return Result.error("未登录");

        List<InventoryItem> items = inventoryService.getUserItems(userId);
        return Result.success(items);
    }

    @PutMapping("/items/{id}")
    public Result<InventoryItem> updateItem(@PathVariable Long id,
                                             @RequestBody Map<String, Object> params,
                                             HttpSession session) {
        String userId = (String) session.getAttribute("user");
        if (userId == null) return Result.error("未登录");

        try {
            InventoryItem item = inventoryService.updateItem(
                    id, userId,
                    (String) params.get("name"),
                    (String) params.get("category"),
                    params.get("quantity") != null ? ((Number) params.get("quantity")).doubleValue() : null,
                    (String) params.get("unit"),
                    (String) params.get("openDate"),
                    params.get("dailyConsumption") != null ? ((Number) params.get("dailyConsumption")).doubleValue() : null,
                    params.get("lowStockThreshold") != null ? ((Number) params.get("lowStockThreshold")).doubleValue() : null,
                    (String) params.get("notes"),
                    params.get("linkedProductId") != null ? ((Number) params.get("linkedProductId")).longValue() : null
            );
            return Result.success(item);
        } catch (Exception e) {
            logger.error("Update inventory item failed", e);
            return Result.error("更新失败：" + e.getMessage());
        }
    }

    @DeleteMapping("/items/{id}")
    public Result<String> deleteItem(@PathVariable Long id, HttpSession session) {
        String userId = (String) session.getAttribute("user");
        if (userId == null) return Result.error("未登录");

        try {
            inventoryService.deleteItem(id, userId);
            return Result.success("删除成功");
        } catch (Exception e) {
            logger.error("Delete inventory item failed", e);
            return Result.error("删除失败：" + e.getMessage());
        }
    }

    @PostMapping("/items/{id}/consume")
    public Result<InventoryItem> consumeItem(@PathVariable Long id,
                                              @RequestBody Map<String, Object> params,
                                              HttpSession session) {
        String userId = (String) session.getAttribute("user");
        if (userId == null) return Result.error("未登录");

        try {
            double amount = ((Number) params.get("amount")).doubleValue();
            InventoryItem item = inventoryService.consumeItem(id, userId, amount);
            return Result.success(item);
        } catch (Exception e) {
            logger.error("Consume inventory item failed", e);
            return Result.error("消耗记录失败：" + e.getMessage());
        }
    }

    @GetMapping("/alerts")
    public Result<List<Map<String, Object>>> getAlerts(HttpSession session) {
        String userId = (String) session.getAttribute("user");
        if (userId == null) return Result.error("未登录");

        return Result.success(inventoryService.getLowStockAlerts(userId));
    }
}
