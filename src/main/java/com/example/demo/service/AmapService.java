package com.example.demo.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.example.demo.config.AmapConfig;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Service
public class AmapService {

    private static final Logger log = LoggerFactory.getLogger(AmapService.class);

    private final OkHttpClient okHttpClient;
    private final AmapConfig amapConfig;

    public AmapService(OkHttpClient okHttpClient, AmapConfig amapConfig) {
        this.okHttpClient = okHttpClient;
        this.amapConfig = amapConfig;
    }

    public GeocodeResult geocode(String address) throws IOException {
        return geocode(address, null);
    }

    public GeocodeResult geocode(String address, String city) throws IOException {
        StringBuilder url = new StringBuilder(amapConfig.getBaseUrl())
                .append("/geocode/geo?key=").append(amapConfig.getApiKey())
                .append("&address=").append(urlEncode(address));
        if (city != null && !city.isBlank()) {
            url.append("&city=").append(urlEncode(city));
        }
        url.append("&output=JSON");

        String body = executeGet(url.toString());
        log.debug("[Amap] Geocode response: {}", body);

        JSONObject json = JSON.parseObject(body);
        if (!"1".equals(json.getString("status"))) {
            throw new IOException("高德地理编码错误: " + json.getString("info"));
        }

        JSONArray geocodes = json.getJSONArray("geocodes");
        if (geocodes == null || geocodes.isEmpty()) {
            throw new IOException("未找到地址对应的坐标: " + address);
        }

        JSONObject geo = geocodes.getJSONObject(0);
        String location = geo.getString("location");
        String[] parts = location.split(",");

        GeocodeResult result = new GeocodeResult();
        result.setLng(parts[0]);
        result.setLat(parts[1]);
        result.setFormattedAddress(geo.getString("formatted_address"));
        result.setProvince(geo.getString("province"));
        result.setCity(geo.getString("city"));
        result.setDistrict(geo.getString("district"));
        result.setAdcode(geo.getString("adcode"));
        return result;
    }

    public List<PoiItem> searchNearbyPois(String lngLat, String keyword, int radius, int offset) throws IOException {
        String url = amapConfig.getBaseUrl() + "/place/around?key=" + amapConfig.getApiKey()
                + "&location=" + lngLat
                + "&keywords=" + urlEncode(keyword)
                + "&radius=" + radius
                + "&offset=" + offset
                + "&extensions=base"
                + "&output=JSON";

        String body = executeGet(url);
        log.debug("[Amap] POI search response: {}", body);

        JSONObject json = JSON.parseObject(body);
        if (!"1".equals(json.getString("status"))) {
            throw new IOException("高德POI搜索错误: " + json.getString("info"));
        }

        JSONArray pois = json.getJSONArray("pois");
        List<PoiItem> results = new ArrayList<>();
        if (pois == null) return results;

        for (int i = 0; i < pois.size(); i++) {
            JSONObject p = pois.getJSONObject(i);
            PoiItem item = new PoiItem();
            item.setId(p.getString("id"));
            item.setName(p.getString("name"));
            item.setAddress(p.getString("address"));
            item.setTel(p.getString("tel"));
            item.setBusinessArea(p.getString("business_area"));
            item.setType(p.getString("type"));
            item.setDistance(p.getString("distance"));

            String loc = p.getString("location");
            if (loc != null) {
                String[] parts = loc.split(",");
                item.setLng(parts.length > 0 ? parts[0] : null);
                item.setLat(parts.length > 1 ? parts[1] : null);
            }

            results.add(item);
        }

        return results;
    }

    public String buildWebNavigationUrl(String fromLng, String fromLat, String fromName,
                                        String toLng, String toLat, String toName) {
        return amapConfig.getWebNavBaseUrl() + "/navigation"
                + "?from=" + fromLng + "," + fromLat
                + "&fromname=" + urlEncode(fromName != null ? fromName : "我的位置")
                + "&to=" + toLng + "," + toLat
                + "&toname=" + urlEncode(toName)
                + "&mode=car"
                + "&src=web"
                + "&coordinate=gaode"
                + "&callnative=1";
    }

    public String buildWebNavigationUrl(String fromLng, String fromLat, String fromName,
                                        PoiItem target) {
        return buildWebNavigationUrl(fromLng, fromLat, fromName,
                target.getLng(), target.getLat(), target.getName());
    }

    public String buildMarkerUrl(String lng, String lat, String name) {
        return amapConfig.getWebNavBaseUrl() + "/marker"
                + "?position=" + lng + "," + lat
                + "&name=" + urlEncode(name)
                + "&src=web"
                + "&coordinate=gaode";
    }

    private String executeGet(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();

        try (Response response = okHttpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP request failed: " + response.code());
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("Empty response body");
            }
            return body.string();
        }
    }

    private String urlEncode(String s) {
        if (s == null) return "";
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    public static class GeocodeResult {
        private String lng;
        private String lat;
        private String formattedAddress;
        private String province;
        private String city;
        private String district;
        private String adcode;

        public String getLng() { return lng; }
        public void setLng(String lng) { this.lng = lng; }
        public String getLat() { return lat; }
        public void setLat(String lat) { this.lat = lat; }
        public String getFormattedAddress() { return formattedAddress; }
        public void setFormattedAddress(String formattedAddress) { this.formattedAddress = formattedAddress; }
        public String getProvince() { return province; }
        public void setProvince(String province) { this.province = province; }
        public String getCity() { return city; }
        public void setCity(String city) { this.city = city; }
        public String getDistrict() { return district; }
        public void setDistrict(String district) { this.district = district; }
        public String getAdcode() { return adcode; }
        public void setAdcode(String adcode) { this.adcode = adcode; }
    }

    public static class PoiItem {
        private String id;
        private String name;
        private String address;
        private String tel;
        private String distance;
        private String lng;
        private String lat;
        private String type;
        private String businessArea;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getAddress() { return address; }
        public void setAddress(String address) { this.address = address; }
        public String getTel() { return tel; }
        public void setTel(String tel) { this.tel = tel; }
        public String getDistance() { return distance; }
        public void setDistance(String distance) { this.distance = distance; }
        public String getLng() { return lng; }
        public void setLng(String lng) { this.lng = lng; }
        public String getLat() { return lat; }
        public void setLat(String lat) { this.lat = lat; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getBusinessArea() { return businessArea; }
        public void setBusinessArea(String businessArea) { this.businessArea = businessArea; }
    }
}
