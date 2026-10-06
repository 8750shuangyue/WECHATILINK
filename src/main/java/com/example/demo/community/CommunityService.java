package com.example.demo.community;

import com.example.demo.chat.LlmService;
import com.example.demo.chat.VectorStoreService;
import com.example.demo.community.entity.Comment;
import com.example.demo.community.entity.Like;
import com.example.demo.community.entity.Post;
import com.example.demo.community.repository.CommentRepository;
import com.example.demo.community.repository.LikeRepository;
import com.example.demo.community.repository.PostRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class CommunityService {

    private static final Logger logger = LoggerFactory.getLogger(CommunityService.class);

    private final PostRepository postRepository;
    private final CommentRepository commentRepository;
    private final LikeRepository likeRepository;
    private final LlmService llmService;
    private final VectorStoreService vectorStoreService;

    public CommunityService(PostRepository postRepository,
                            CommentRepository commentRepository,
                            LikeRepository likeRepository,
                            LlmService llmService,
                            VectorStoreService vectorStoreService) {
        this.postRepository = postRepository;
        this.commentRepository = commentRepository;
        this.likeRepository = likeRepository;
        this.llmService = llmService;
        this.vectorStoreService = vectorStoreService;
    }

    public Post createPost(String userId, String userName, String title, String content, String category, String imageUrl) {
        Post post = Post.builder()
                .userId(userId)
                .userName(userName)
                .title(title)
                .content(content)
                .category(category != null ? category : "GENERAL")
                .imageUrl(imageUrl)
                .build();

        // Auto-generate tags using LLM
        try {
            String tagPrompt = "请为以下内容生成3-5个相关标签，用逗号分隔，每个标签以#开头，如：#猫咪呕吐 #多肉徒长。内容：" + content;
            String tagResult = llmService.chat(tagPrompt);
            if (tagResult != null && !tagResult.isEmpty()) {
                post.setTags(tagResult.trim());
            }
        } catch (Exception e) {
            logger.warn("Failed to auto-generate tags: {}", e.getMessage());
        }

        Post saved = postRepository.save(post);
        logger.info("Post created, id: {}, userId: {}", saved.getId(), userId);

        // Index into vector store for RAG
        try {
            vectorStoreService.saveDocument("post_" + saved.getId(), content, Map.of(
                    "type", "community_post",
                    "postId", saved.getId(),
                    "title", title,
                    "category", category != null ? category : "GENERAL"
            ));
            saved.setIsIndexed(true);
            postRepository.save(saved);
        } catch (Exception e) {
            logger.warn("Failed to index post into vector store: {}", e.getMessage());
        }

        return saved;
    }

    public Page<Post> getPosts(String category, String tag, int page, int size) {
        int pageIndex = Math.max(0, page - 1);
        Pageable pageable = PageRequest.of(pageIndex, size, Sort.by(Sort.Direction.DESC, "createdAt"));

        if (tag != null && !tag.isEmpty()) {
            return postRepository.findByTagsContaining(tag, pageable);
        }
        if (category != null && !category.isEmpty()) {
            return postRepository.findByCategoryOrderByCreatedAtDesc(category, pageable);
        }
        return postRepository.findAll(pageable);
    }

    public Post getPostDetail(Long postId) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new RuntimeException("帖子不存在"));
        post.setViewCount(post.getViewCount() + 1);
        postRepository.save(post);
        return post;
    }

    public void deletePost(Long postId, String userId) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new RuntimeException("帖子不存在"));
        if (!post.getUserId().equals(userId)) {
            throw new RuntimeException("无权删除此帖子");
        }
        postRepository.delete(post);
        logger.info("Post deleted, id: {}, userId: {}", postId, userId);
    }

    public Comment addComment(Long postId, String userId, String userName, String content, Long parentId) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new RuntimeException("帖子不存在"));

        Comment comment = Comment.builder()
                .postId(postId)
                .userId(userId)
                .userName(userName)
                .content(content)
                .parentId(parentId)
                .build();

        Comment saved = commentRepository.save(comment);

        post.setCommentCount(post.getCommentCount() + 1);
        postRepository.save(post);

        logger.info("Comment added, id: {}, postId: {}", saved.getId(), postId);
        return saved;
    }

    public boolean toggleLike(Long postId, String userId) {
        Optional<Like> existingLike = likeRepository.findByUserIdAndPostId(userId, postId);
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new RuntimeException("帖子不存在"));

        if (existingLike.isPresent()) {
            likeRepository.delete(existingLike.get());
            post.setLikeCount(Math.max(0, post.getLikeCount() - 1));
            postRepository.save(post);
            logger.info("Like removed, postId: {}, userId: {}", postId, userId);
            return false;
        } else {
            Like like = Like.builder()
                    .userId(userId)
                    .postId(postId)
                    .build();
            likeRepository.save(like);
            post.setLikeCount(post.getLikeCount() + 1);
            postRepository.save(post);
            logger.info("Like added, postId: {}, userId: {}", postId, userId);
            return true;
        }
    }

    public List<String> getPopularTags() {
        List<Post> allPosts = postRepository.findAll();
        Map<String, Long> tagCountMap = new HashMap<>();

        for (Post post : allPosts) {
            if (post.getTags() != null && !post.getTags().isEmpty()) {
                String[] tags = post.getTags().split("[,#]+");
                for (String tag : tags) {
                    String trimmed = tag.trim();
                    if (!trimmed.isEmpty()) {
                        tagCountMap.merge(trimmed, 1L, Long::sum);
                    }
                }
            }
        }

        return tagCountMap.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(20)
                .map(entry -> "#" + entry.getKey())
                .collect(Collectors.toList());
    }
}
