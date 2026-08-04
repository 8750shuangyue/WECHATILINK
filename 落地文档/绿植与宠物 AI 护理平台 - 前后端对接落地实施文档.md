# 绿植与宠物 AI 护理平台 - 前后端对接落地实施文档

## 1. 数据库设计 (SQL)

```sql
-- 植物档案表
CREATE TABLE plant_profile (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键 ID',
    name VARCHAR(100) NOT NULL COMMENT '植物名称',
    species VARCHAR(100) COMMENT '品种',
    ai_image_url VARCHAR(500) COMMENT 'AI 识别图片 URL',
    care_tips TEXT COMMENT 'AI 生成的养护建议',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
);

-- 宠物档案表
CREATE TABLE pet_profile (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键 ID',
    name VARCHAR(100) NOT NULL COMMENT '宠物名称',
    species VARCHAR(100) COMMENT '品种',
    ai_image_url VARCHAR(500) COMMENT 'AI 识别图片 URL',
    health_status VARCHAR(50) COMMENT 'AI 健康状态评估',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
);
```

## 2. 后端核心代码 (Spring Boot)

### 2.1 统一返回类

```java
package com.aicare.common;

import lombok.Data;

@Data
public class Result<T> {
    private Integer code;
    private String message;
    private T data;

    public static <T> Result<T> success(T data) {
        Result<T> result = new Result<>();
        result.setCode(200);
        result.setMessage("操作成功");
        result.setData(data);
        return result;
    }

    public static <T> Result<T> error(String message) {
        Result<T> result = new Result<>();
        result.setCode(500);
        result.setMessage(message);
        return result;
    }
}
```

### 2.2 跨域配置

```java
package com.aicare.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("**")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("**")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
```

### 2.3 植物模块控制器

```java
package com.aicare.controller;

import com.aicare.common.Result;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/plant")
public class PlantController {

    @PostMapping("/recognize")
    public Result<Map<String, Object>> recognize(@RequestParam("file") MultipartFile file) {
        // TODO: 调用 AI 图像识别服务
        Map<String, Object> aiResult = new HashMap<>();
        aiResult.put("species", "绿萝");
        aiResult.put("careTips", "喜半阴环境，保持土壤微湿，避免强光直射。");
        return Result.success(aiResult);
    }

    @GetMapping("/list")
    public Result<Object> list() {
        // TODO: 查询植物档案列表
        return Result.success(null);
    }
}
```

### 2.4 宠物模块控制器

```java
package com.aicare.controller;

import com.aicare.common.Result;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/pet")
public class PetController {

    @PostMapping("/recognize")
    public Result<Map<String, Object>> recognize(@RequestParam("file") MultipartFile file) {
        // TODO: 调用 AI 宠物识别与健康评估服务
        Map<String, Object> aiResult = new HashMap<>();
        aiResult.put("species", "中华田园猫");
        aiResult.put("healthStatus", "健康，建议定期驱虫。");
        return Result.success(aiResult);
    }

    @GetMapping("/list")
    public Result<Object> list() {
        // TODO: 查询宠物档案列表
        return Result.success(null);
    }
}
```

## 3. 前端核心代码 (Vue3 + Element Plus)

```html
<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <title>绿植与宠物 AI 护理平台</title>
    <link rel="stylesheet" href="https://unpkg.com/element-plus/dist/index.css">
    <script src="https://unpkg.com/vue@3/dist/vue.global.js"></script>
    <script src="https://unpkg.com/element-plus"></script>
</head>
<body>
    <div id="app">
        <el-container style="min-height: 100vh;">
            <el-header style="display: flex; align-items: center; justify-content: space-between;">
                <h2>🌿 AI 护理平台</h2>
                <el-radio-group v-model="currentModule" @change="switchModule">
                    <el-radio-button label="plant">植物护理</el-radio-button>
                    <el-radio-button label="pet">宠物护理</el-radio-button>
                </el-radio-group>
            </el-header>
            <el-main>
                <el-upload
                    action="#"
                    :auto-upload="false"
                    :on-change="handleFileChange"
                    :show-file-list="false"
                    accept="image/*">
                    <el-button type="primary">上传 {{ currentModule === 'plant' ? '植物' : '宠物' }}照片</el-button>
                </el-upload>
                <el-card v-if="aiResult" style="margin-top: 20px;">
                    <template #header>AI 识别结果</template>
                    <p><strong>品种：</strong>{{ aiResult.species }}</p>
                    <p><strong>建议：</strong>{{ currentModule === 'plant' ? aiResult.careTips : aiResult.healthStatus }}</p>
                </el-card>
            </el-main>
        </el-container>
    </div>

    <script>
        const { createApp, ref } = Vue;
        const app = createApp({
            setup() {
                const currentModule = ref('plant');
                const aiResult = ref(null);

                const switchModule = () => {
                    aiResult.value = null; // 切换模块时清空结果
                };

                const handleFileChange = async (uploadFile) => {
                    const formData = new FormData();
                    formData.append('file', uploadFile.raw);

                    const url = currentModule.value === 'plant'
                        ? '/api/plant/recognize'
                        : '/api/pet/recognize';

                    // 模拟请求
                    // const res = await fetch(url, { method: 'POST', body: formData });
                    // aiResult.value = (await res.json()).data;

                    // 演示用假数据
                    aiResult.value = currentModule.value === 'plant'
                        ? { species: '绿萝', careTips: '喜半阴环境，保持土壤微湿。' }
                        : { species: '中华田园猫', healthStatus: '健康，建议定期驱虫。' };
                };

                return { currentModule, aiResult, switchModule, handleFileChange };
            }
        });
        app.use(ElementPlus);
        app.mount('#app');
    </script>
</body>
</html>
```

## 4. 环境准备与启动步骤

1. **环境要求**：JDK 17+，Node.js (可选，用于前端热更新，当前为 CDN 单文件)，MySQL 8.0+
2. **数据库初始化**：执行第 1 节 SQL 脚本。
3. **后端启动**：
    - 配置 `application.yml` 中的数据库连接及 AI 服务密钥。
    - 运行 `SpringBootApplication` 主类。
4. **前端部署**：
    - 将 `index.html` 放入后端 `resources/static` 目录，或使用 Live Server 等工具直接打开。
    - 确保前端请求地址与后端端口一致 (默认 8080)。

