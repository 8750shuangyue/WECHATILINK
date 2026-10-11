package com.example.demo.community;

import com.example.demo.chat.VectorStoreService;
import com.example.demo.community.entity.Post;
import com.example.demo.community.repository.PostRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
public class CommunityVectorGovernanceService {

    private static final Logger logger = LoggerFactory.getLogger(CommunityVectorGovernanceService.class);
    private static final String POST_SOURCE_PREFIX = "post_";

    private final VectorStoreService vectorStoreService;
    private final PostRepository postRepository;

    @Value("${app.data-governance.enabled:false}")
    private boolean governanceEnabled;

    public CommunityVectorGovernanceService(VectorStoreService vectorStoreService,
                                            PostRepository postRepository) {
        this.vectorStoreService = vectorStoreService;
        this.postRepository = postRepository;
    }

    @Scheduled(cron = "${community.vector-governance.orphan-cleanup-cron:0 30 3 * * *}")
    public int cleanupOrphanPostVectors() {
        if (!governanceEnabled) {
            return 0;
        }
        List<Long> postIds = extractPostIds(vectorStoreService.listPublicSourceIds());
        if (postIds.isEmpty()) {
            return 0;
        }

        Set<Long> existingPostIds = new HashSet<>();
        for (Post post : postRepository.findAllById(postIds)) {
            if (post.getId() != null) {
                existingPostIds.add(post.getId());
            }
        }

        List<String> failures = new ArrayList<>();
        int cleared = 0;
        for (Long postId : postIds) {
            if (existingPostIds.contains(postId)) {
                continue;
            }

            String sourceId = POST_SOURCE_PREFIX + postId;
            try {
                vectorStoreService.clearDocumentVectorsStrict(sourceId);
                cleared++;
            } catch (Exception e) {
                failures.add(sourceId);
                logger.error("Failed to clear orphan community post vector, sourceId: {}", sourceId, e);
            }
        }

        if (!failures.isEmpty()) {
            throw new IllegalStateException("Failed to clear orphan community post vectors: " + failures);
        }
        if (cleared > 0) {
            logger.info("Cleared {} orphan community post vector sources", cleared);
        }
        return cleared;
    }

    private List<Long> extractPostIds(List<String> sourceIds) {
        return sourceIds.stream()
                .filter(Objects::nonNull)
                .filter(sourceId -> sourceId.startsWith(POST_SOURCE_PREFIX))
                .map(this::parsePostId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    private Long parsePostId(String sourceId) {
        String rawId = sourceId.substring(POST_SOURCE_PREFIX.length());
        if (rawId.isEmpty()) {
            return null;
        }
        try {
            long postId = Long.parseLong(rawId);
            return postId > 0 ? postId : null;
        } catch (NumberFormatException e) {
            logger.warn("Ignoring malformed community post sourceId: {}", sourceId);
            return null;
        }
    }
}
