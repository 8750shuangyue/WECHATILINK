package com.example.demo.care.service;

import com.alibaba.fastjson2.JSONObject;
import com.example.demo.agent.tools.ToolResult;
import com.example.demo.agent.tools.WebSearchTool;
import com.example.demo.service.AmapService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class NearbyServiceSearchService {

    private static final Logger log = LoggerFactory.getLogger(NearbyServiceSearchService.class);

    private static final ExecutorService appointmentExecutor = Executors.newFixedThreadPool(3);

    private final AmapService amapService;
    private final WebSearchTool webSearchTool;

    public NearbyServiceSearchService(AmapService amapService, WebSearchTool webSearchTool) {
        this.amapService = amapService;
        this.webSearchTool = webSearchTool;
    }

    @PreDestroy
    public void shutdown() {
        appointmentExecutor.shutdown();
        log.info("NearbyServiceSearchService appointment executor shut down");
    }

    public String searchNearbyService(String serviceType, String location) {
        if (location == null || location.isBlank()) {
            return "请提供具体的位置信息（如：杭州市余杭区文一西路）";
        }

        String typeLabel = getServiceTypeLabel(serviceType);
        String keyword = getSearchKeyword(serviceType);

        try {
            log.info("[NearbySearch] Geocoding location: {}", location);
            AmapService.GeocodeResult geo = amapService.geocode(location);
            log.info("[NearbySearch] Geocoded: lng={}, lat={}, formatted={}",
                    geo.getLng(), geo.getLat(), geo.getFormattedAddress());

            log.info("[NearbySearch] Searching POI: keyword={}, location={}, city={}",
                    keyword, location, geo.getCity());

            List<AmapService.PoiItem> pois = amapService.searchNearbyPois(
                    geo.getLng() + "," + geo.getLat(),
                    keyword,
                    5000,
                    5
            );

            StringBuilder sb = new StringBuilder();
            sb.append("🏥 【附近").append(typeLabel).append("】\n\n");

            if (pois.isEmpty()) {
                sb.append("😢 抱歉，在该地点附近 5 公里内没有找到").append(typeLabel).append("。");
                sb.append("\n\n💡 建议：");
                sb.append("\n- 尝试扩大搜索范围或使用更精确的地址");
                sb.append("\n- 通过高德地图 App 搜索");
                sb.append("\n- 查看以下定位链接确认位置是否正确：");
                sb.append(amapService.buildMarkerUrl(geo.getLng(), geo.getLat(), location));
                return sb.toString();
            }

            sb.append("✅ 共找到 ").append(pois.size()).append(" 家").append(typeLabel).append("：\n");

            int appointmentCount = Math.min(3, pois.size());
            List<StringBuilder> blocks = new ArrayList<>();
            CompletableFuture<String>[] appointmentFutures = new CompletableFuture[appointmentCount];

            for (int i = 0; i < pois.size(); i++) {
                AmapService.PoiItem poi = pois.get(i);
                int distanceMeters = parseDistance(poi.getDistance());
                StringBuilder block = new StringBuilder();

                block.append("\n📍 ").append(poi.getName());
                if (distanceMeters >= 1000) {
                    block.append(" (").append(String.format("%.1f", distanceMeters / 1000.0)).append("km)");
                } else {
                    block.append(" (").append(distanceMeters).append("m)");
                }
                block.append("\n");

                if (poi.getAddress() != null && !poi.getAddress().isBlank()) {
                    block.append("   地址：").append(poi.getAddress()).append("\n");
                }
                if (poi.getTel() != null && !poi.getTel().isBlank()) {
                    block.append("   电话：").append(poi.getTel()).append("\n");
                }

                String navUrl = amapService.buildWebNavigationUrl(
                        geo.getLng(), geo.getLat(), "我的位置",
                        poi.getLng(), poi.getLat(), poi.getName()
                );

                block.append("   🧭 导航直达：点我打开高德地图 → ").append(navUrl).append("\n");
                blocks.add(block);

                if (i < appointmentCount) {
                    final String name = poi.getName();
                    appointmentFutures[i] = CompletableFuture.supplyAsync(
                            () -> searchAppointmentInfo(name), appointmentExecutor);
                }
            }

            if (appointmentCount > 0) {
                try {
                    CompletableFuture.allOf(appointmentFutures).join();
                    for (int i = 0; i < appointmentCount; i++) {
                        String info = appointmentFutures[i].get();
                        if (info != null && !info.isBlank()) {
                            blocks.get(i).append(info);
                        }
                    }
                } catch (Exception e) {
                    log.warn("[NearbySearch] Parallel appointment search failed: {}", e.getMessage());
                }
            }

            for (StringBuilder block : blocks) {
                sb.append(block);
            }

            return sb.toString();

        } catch (Exception e) {
            log.error("[NearbySearch] Search failed: {}", e.getMessage(), e);
            return "附近服务搜索出错：" + e.getMessage();
        }
    }

    private int parseDistance(String distance) {
        if (distance == null || distance.isBlank()) return 0;
        try {
            return (int) Math.round(Double.parseDouble(distance));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String getSearchKeyword(String serviceType) {
        if (serviceType == null) return "宠物医院";
        return switch (serviceType) {
            case "hospital" -> "宠物医院";
            case "emergency" -> "宠物急诊";
            case "clinic" -> "宠物诊所";
            case "plant_hospital" -> "植物医院";
            case "gardening" -> "园艺店";
            case "pet_shop" -> "宠物店";
            case "grooming" -> "宠物美容";
            default -> "宠物医院";
        };
    }

    private String getServiceTypeLabel(String serviceType) {
        if (serviceType == null) return "宠物服务";
        return switch (serviceType) {
            case "hospital" -> "宠物医院";
            case "emergency" -> "24小时宠物急诊";
            case "clinic" -> "宠物诊所";
            case "plant_hospital" -> "植物医院";
            case "gardening" -> "园艺店";
            case "pet_shop" -> "宠物店";
            case "grooming" -> "宠物美容";
            default -> "宠物服务";
        };
    }

    private String searchAppointmentInfo(String hospitalName) {
        try {
            String query = hospitalName + " 预约挂号 微信小程序 公众号";

            JSONObject params = new JSONObject();
            params.put("query", query);
            params.put("count", 3);

            ToolResult<?> result = webSearchTool.execute(params);

            StringBuilder sb = new StringBuilder();
            sb.append("   📅 预约挂号：\n");

            if (result.isSuccess() && result.getData() != null) {
                String data = result.getData().toString();
                if (!data.isBlank()) {
                    String summary = data.length() > 200 ? data.substring(0, 200) + "..." : data;
                    sb.append("   ").append(summary).append("\n");
                } else {
                    sb.append("   暂未找到公开预约方式，建议电话咨询\n");
                }
            } else {
                sb.append("   暂未找到公开预约方式，建议电话咨询\n");
            }

            return sb.toString();
        } catch (Exception e) {
            log.warn("[NearbySearch] Appointment search failed for {}: {}", hospitalName, e.getMessage());
            return "   📅 预约挂号：暂未找到公开预约方式，建议电话咨询\n";
        }
    }
}
