package com.example.demo.community;

import com.example.demo.aicare.Result;
import com.example.demo.community.entity.Comment;
import com.example.demo.community.entity.Post;
import com.example.demo.community.repository.CommentRepository;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/community")
public class CommunityController {

    private static final Logger logger = LoggerFactory.getLogger(CommunityController.class);

    private final CommunityService communityService;
    private final CommentRepository commentRepository;

    public CommunityController(CommunityService communityService,
                               CommentRepository commentRepository) {
        this.communityService = communityService;
        this.commentRepository = commentRepository;
    }

    @PostMapping("/posts")
    public Result<Post> createPost(@RequestBody Map<String, String> body, HttpSession session) {
        String userId = (String) session.getAttribute("user");
        String userName = (String) session.getAttribute("userName");
        if (userId == null) {
            return Result.error("请先登录");
        }

        try {
            String title = body.get("title");
            String content = body.get("content");
            String category = body.getOrDefault("category", "GENERAL");
            String imageUrl = body.get("imageUrl");

            if (title == null || title.trim().isEmpty()) {
                return Result.error("标题不能为空");
            }
            if (content == null || content.trim().isEmpty()) {
                return Result.error("内容不能为空");
            }

            Post post = communityService.createPost(userId, userName != null ? userName : userId,
                    title, content, category, imageUrl);
            return Result.success(post);
        } catch (Exception e) {
            logger.error("Create post failed", e);
            return Result.error("发布失败：" + e.getMessage());
        }
    }

    @GetMapping("/posts")
    public Result<Map<String, Object>> getPosts(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String tag,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        try {
            Page<Post> postPage = communityService.getPosts(category, tag, page, size);
            Map<String, Object> result = new HashMap<>();
            result.put("content", postPage.getContent());
            result.put("totalPages", postPage.getTotalPages());
            result.put("totalElements", postPage.getTotalElements());
            result.put("currentPage", page);
            return Result.success(result);
        } catch (Exception e) {
            logger.error("Get posts failed", e);
            return Result.error("获取帖子列表失败：" + e.getMessage());
        }
    }

    @GetMapping("/posts/{id}")
    public Result<Map<String, Object>> getPostDetail(@PathVariable Long id) {
        try {
            Post post = communityService.getPostDetail(id);
            List<Comment> comments = commentRepository.findByPostIdOrderByCreatedAtAsc(id);
            Map<String, Object> result = new HashMap<>();
            result.put("post", post);
            result.put("comments", comments);
            return Result.success(result);
        } catch (Exception e) {
            logger.error("Get post detail failed, id: {}", id, e);
            return Result.error("获取帖子详情失败：" + e.getMessage());
        }
    }

    @DeleteMapping("/posts/{id}")
    public Result<String> deletePost(@PathVariable Long id, HttpSession session) {
        String userId = (String) session.getAttribute("user");
        if (userId == null) {
            return Result.error("请先登录");
        }

        try {
            communityService.deletePost(id, userId);
            return Result.success("删除成功");
        } catch (Exception e) {
            logger.error("Delete post failed, id: {}", id, e);
            return Result.error("删除失败：" + e.getMessage());
        }
    }

    @PostMapping("/posts/{id}/comments")
    public Result<Comment> addComment(@PathVariable Long id, @RequestBody Map<String, Object> body,
                                      HttpSession session) {
        String userId = (String) session.getAttribute("user");
        String userName = (String) session.getAttribute("userName");
        if (userId == null) {
            return Result.error("请先登录");
        }

        try {
            String content = (String) body.get("content");
            if (content == null || content.trim().isEmpty()) {
                return Result.error("评论内容不能为空");
            }

            Long parentId = null;
            if (body.containsKey("parentId") && body.get("parentId") != null) {
                Object parentIdObj = body.get("parentId");
                if (parentIdObj instanceof Number) {
                    parentId = ((Number) parentIdObj).longValue();
                } else if (parentIdObj instanceof String) {
                    parentId = Long.parseLong((String) parentIdObj);
                }
            }

            Comment comment = communityService.addComment(id, userId,
                    userName != null ? userName : userId, content, parentId);
            return Result.success(comment);
        } catch (Exception e) {
            logger.error("Add comment failed, postId: {}", id, e);
            return Result.error("评论失败：" + e.getMessage());
        }
    }

    @PostMapping("/posts/{id}/like")
    public Result<Map<String, Object>> toggleLike(@PathVariable Long id, HttpSession session) {
        String userId = (String) session.getAttribute("user");
        if (userId == null) {
            return Result.error("请先登录");
        }

        try {
            boolean liked = communityService.toggleLike(id, userId);
            Map<String, Object> result = new HashMap<>();
            result.put("liked", liked);
            result.put("postId", id);
            return Result.success(result);
        } catch (Exception e) {
            logger.error("Toggle like failed, postId: {}", id, e);
            return Result.error("操作失败：" + e.getMessage());
        }
    }

    @GetMapping("/tags")
    public Result<List<String>> getPopularTags() {
        try {
            List<String> tags = communityService.getPopularTags();
            return Result.success(tags);
        } catch (Exception e) {
            logger.error("Get popular tags failed", e);
            return Result.error("获取标签失败：" + e.getMessage());
        }
    }
}
