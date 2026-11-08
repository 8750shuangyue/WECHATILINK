package com.example.demo.config;

import com.example.demo.aicare.Result;
import com.example.demo.care.CareController;
import com.example.demo.community.CommunityController;
import com.example.demo.gallery.GalleryController;
import com.example.demo.kb.KnowledgeBaseController;
import com.example.demo.stats.StatsController;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 已迁移的控制器必须只返回 Result 信封（可直接返回，或包在 ResponseEntity 里）。
 */
class ResultEnvelopeContractTest {

    private static final List<Class<?>> MIGRATED = List.of(
            StatsController.class,
            KnowledgeBaseController.class,
            GalleryController.class,
            CommunityController.class,
            CareController.class
    );

    @Test
    void migratedControllersOnlyReturnResultEnvelopes() {
        for (Class<?> type : MIGRATED) {
            for (Method method : type.getDeclaredMethods()) {
                if (!isEndpoint(method)) {
                    continue;
                }
                assertTrue(isResultEnvelope(method.getGenericReturnType()),
                        type.getSimpleName() + "." + method.getName()
                                + " 返回了非 Result 类型: " + method.getGenericReturnType());
            }
        }
    }

    private static boolean isEndpoint(Method method) {
        return method.isAnnotationPresent(GetMapping.class)
                || method.isAnnotationPresent(PostMapping.class)
                || method.isAnnotationPresent(PutMapping.class)
                || method.isAnnotationPresent(DeleteMapping.class);
    }

    private static boolean isResultEnvelope(Type type) {
        if (type instanceof Class<?> clazz) {
            return Result.class.isAssignableFrom(clazz);
        }
        if (type instanceof ParameterizedType parameterized) {
            Type raw = parameterized.getRawType();
            if (raw == ResponseEntity.class) {
                return isResultEnvelope(parameterized.getActualTypeArguments()[0]);
            }
            return raw instanceof Class<?> clazz && Result.class.isAssignableFrom(clazz);
        }
        return false;
    }
}
