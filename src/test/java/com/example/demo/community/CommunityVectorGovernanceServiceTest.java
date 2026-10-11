package com.example.demo.community;

import com.example.demo.chat.VectorStoreService;
import com.example.demo.community.entity.Post;
import com.example.demo.community.repository.PostRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CommunityVectorGovernanceServiceTest {

    private VectorStoreService vectorStoreService;
    private PostRepository postRepository;
    private CommunityVectorGovernanceService service;

    @BeforeEach
    void setUp() {
        vectorStoreService = mock(VectorStoreService.class);
        postRepository = mock(PostRepository.class);
        service = new CommunityVectorGovernanceService(vectorStoreService, postRepository);
        ReflectionTestUtils.setField(service, "governanceEnabled", true);
    }

    @Test
    void clearsOnlyVectorsForMissingCommunityPosts() {
        when(vectorStoreService.listPublicSourceIds()).thenReturn(List.of(
                "post_1", "post_2", "post_bad", "post_0", "guide-1"
        ));
        when(postRepository.findAllById(List.of(1L, 2L)))
                .thenReturn(List.of(Post.builder().id(1L).build()));

        int cleared = service.cleanupOrphanPostVectors();

        assertEquals(1, cleared);
        verify(vectorStoreService).clearDocumentVectorsStrict("post_2");
        verify(vectorStoreService, never()).clearDocumentVectorsStrict("post_1");
        verify(vectorStoreService, never()).clearDocumentVectorsStrict("guide-1");
    }

    @Test
    void reportsCleanupFailuresInsteadOfSilentlyLeavingOrphans() {
        when(vectorStoreService.listPublicSourceIds()).thenReturn(List.of("post_2", "post_3"));
        when(postRepository.findAllById(List.of(2L, 3L))).thenReturn(List.of());
        doThrow(new IllegalStateException("sqlite unavailable"))
                .when(vectorStoreService)
                .clearDocumentVectorsStrict("post_2");

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> service.cleanupOrphanPostVectors()
        );

        assertEquals(
                "Failed to clear orphan community post vectors: [post_2]",
                error.getMessage()
        );
        verify(vectorStoreService).clearDocumentVectorsStrict("post_2");
        verify(vectorStoreService).clearDocumentVectorsStrict("post_3");
    }

    @Test
    void skipsOrphanCleanupWhenGovernanceIsDisabled() {
        ReflectionTestUtils.setField(service, "governanceEnabled", false);

        assertEquals(0, service.cleanupOrphanPostVectors());

        verifyNoInteractions(vectorStoreService, postRepository);
    }
}
