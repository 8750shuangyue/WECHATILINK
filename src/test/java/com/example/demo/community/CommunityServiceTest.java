package com.example.demo.community;

import com.example.demo.chat.LlmService;
import com.example.demo.chat.VectorStoreService;
import com.example.demo.community.entity.Post;
import com.example.demo.community.repository.CommentRepository;
import com.example.demo.community.repository.LikeRepository;
import com.example.demo.community.repository.PostRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommunityServiceTest {

    private PostRepository postRepository;
    private VectorStoreService vectorStoreService;
    private CommunityService service;

    @BeforeEach
    void setUp() {
        postRepository = mock(PostRepository.class);
        vectorStoreService = mock(VectorStoreService.class);
        service = new CommunityService(
                postRepository,
                mock(CommentRepository.class),
                mock(LikeRepository.class),
                mock(LlmService.class),
                vectorStoreService
        );
    }

    @Test
    void deletePostFlushesBusinessDeleteBeforeStrictVectorCleanup() {
        Post post = post(42L, "user-1");
        when(postRepository.findById(42L)).thenReturn(Optional.of(post));

        service.deletePost(42L, "user-1");

        verify(postRepository).delete(post);
        verify(postRepository).flush();
        verify(vectorStoreService).clearDocumentVectorsStrict("post_42");
    }

    @Test
    void deletePostRejectsNonOwnerWithoutDeletingAnything() {
        Post post = post(42L, "owner-1");
        when(postRepository.findById(42L)).thenReturn(Optional.of(post));

        RuntimeException error = assertThrows(
                RuntimeException.class,
                () -> service.deletePost(42L, "other-user")
        );

        assertEquals("无权删除此帖子", error.getMessage());
        verify(postRepository, never()).delete(post);
        verify(postRepository, never()).flush();
        verify(vectorStoreService, never()).clearDocumentVectorsStrict("post_42");
    }

    @Test
    void deletePostPropagatesVectorCleanupFailureForTransactionRollback() {
        Post post = post(42L, "user-1");
        when(postRepository.findById(42L)).thenReturn(Optional.of(post));
        doThrow(new IllegalStateException("sqlite unavailable"))
                .when(vectorStoreService)
                .clearDocumentVectorsStrict("post_42");

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> service.deletePost(42L, "user-1")
        );

        assertEquals("sqlite unavailable", error.getMessage());
        verify(vectorStoreService).clearDocumentVectorsStrict("post_42");
    }

    @Test
    void deletePostRunsInMySqlTransaction() throws Exception {
        Transactional transactional = CommunityService.class
                .getMethod("deletePost", Long.class, String.class)
                .getAnnotation(Transactional.class);

        assertNotNull(transactional, "deletePost must be transactional");
    }

    private Post post(Long id, String userId) {
        return Post.builder()
                .id(id)
                .userId(userId)
                .userName(userId)
                .title("title")
                .content("content")
                .build();
    }
}
