package cc.ryanc.halo.web.controller.front;

import cc.ryanc.halo.model.domain.Comment;
import cc.ryanc.halo.model.domain.Post;
import cc.ryanc.halo.model.dto.HaloConst;
import cc.ryanc.halo.model.dto.JsonResult;
import cc.ryanc.halo.model.enums.*;
import cc.ryanc.halo.service.CommentService;
import cc.ryanc.halo.service.MailService;
import cc.ryanc.halo.service.PostService;
import cc.ryanc.halo.service.UserService;
import cc.ryanc.halo.utils.CommentUtil;
import cc.ryanc.halo.utils.OwoUtil;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.lang.Validator;
import cn.hutool.core.util.URLUtil;
import cn.hutool.extra.servlet.ServletUtil;
import cn.hutool.http.HtmlUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * @author : RYAN0UP
 * @date : 2018/4/26
 */
@Slf4j
@Controller
public class FrontCommentController {

    @Autowired
    private CommentService commentService;

    @Autowired
    private PostService postService;

    @Autowired
    private UserService userService;

    @Autowired
    private MailService mailService;

    /**
     * 获取文章的评论
     *
     * @param postId postId 文章编号
     * @return List
     */
    @GetMapping(value = "/getComment/{postId}", produces = MediaType.APPLICATION_JSON_UTF8_VALUE)
    @ResponseBody
    public List<Comment> getComment(@PathVariable Long postId) {
        Optional<Post> post = postService.findByPostId(postId);
        Sort sort = new Sort(Sort.Direction.DESC, "commentDate");
        Pageable pageable = PageRequest.of(0, 999, sort);
        List<Comment> comments = commentService.findCommentsByPostAndCommentStatus(post.get(), pageable, CommentStatus.PUBLISHED.getCode()).getContent();
        return CommentUtil.getComments(comments);
    }

    /**
     * 加载评论
     *
     * @param page 页码
     * @param post 当前文章
     * @return List
     */
    @GetMapping(value = "/loadComment")
    @ResponseBody
    public List<Comment> loadComment(@RequestParam(value = "page") Integer page,
                                     @RequestParam(value = "post") Post post) {
        Sort sort = new Sort(Sort.Direction.DESC, "commentDate");
        Pageable pageable = PageRequest.of(page - 1, 10, sort);
        List<Comment> comments = commentService.findCommentsByPostAndCommentStatus(post, pageable, CommentStatus.PUBLISHED.getCode()).getContent();
        return comments;
    }

    /**
     * 提交新评论
     *
     * @param comment comment实体
     * @param post    post实体
     * @param request request
     * @return JsonResult
     */
    @PostMapping(value = "/newComment")
    @ResponseBody
    public JsonResult newComment(@Valid @ModelAttribute("comment") Comment comment,
                                 BindingResult result,
                                 @ModelAttribute("post") Post post,
                                 HttpServletRequest request) {
        if (result.hasErrors()) {
            for (ObjectError error : result.getAllErrors()) {
                return new JsonResult(ResultCode.FAIL.getCode(), error.getDefaultMessage());
            }
        }
        try {
            Comment lastComment = null;
            post = postService.findByPostId(post.getPostId()).get();
            comment.setCommentAuthorEmail(HtmlUtil.encode(comment.getCommentAuthorEmail()).toLowerCase());
            comment.setPost(post);
            comment.setCommentDate(DateUtil.date());
            comment.setCommentAuthorIp(ServletUtil.getClientIP(request));
            comment.setIsAdmin(0);
            comment.setCommentAuthor(HtmlUtil.encode(comment.getCommentAuthor()));
            if (comment.getCommentParent() > 0) {
                lastComment = commentService.findCommentById(comment.getCommentParent()).get();
                String lastContent = "<a href='#comment-id-" + lastComment.getCommentId() + "'>@" + lastComment.getCommentAuthor() + "</a>";
                comment.setCommentContent(lastContent + StringUtils.substringAfter(OwoUtil.markToImg(HtmlUtil.encode(comment.getCommentContent())), ":"));
            } else {
                //将评论内容的字符专为安全字符
                comment.setCommentContent(OwoUtil.markToImg(HtmlUtil.encode(comment.getCommentContent())));
            }
            if (StringUtils.isNotEmpty(comment.getCommentAuthorUrl())) {
                comment.setCommentAuthorUrl(URLUtil.formatUrl(comment.getCommentAuthorUrl()));
            }
            commentService.saveByComment(comment);
            if (comment.getCommentParent() > 0) {
                new EmailToParent(comment, lastComment, post).start();
                new EmailToAdmin(comment, post).start();
            } else {
                new EmailToAdmin(comment, post).start();
            }
            if (StringUtils.equals(HaloConst.OPTIONS.get(BlogProperties.NEW_COMMENT_NEED_CHECK.getProp()), TrueFalse.TRUE.getDesc()) || HaloConst.OPTIONS.get(BlogProperties.NEW_COMMENT_NEED_CHECK.getProp()) == null) {
                return new JsonResult(ResultCode.SUCCESS.getCode(), "你的评论已经提交，待博主审核之后可显示。");
            } else {
                return new JsonResult(ResultCode.SUCCESS.getCode(), "你的评论已经提交，刷新后即可显示。");
            }
        } catch (Exception e) {
            return new JsonResult(ResultCode.FAIL.getCode(), "评论失败！");
        }
    }

    /**
     * 发送邮件给博主
     */
    class EmailToAdmin extends Thread {
        private Comment comment;
        private Post post;

        private EmailToAdmin(Comment comment, Post post) {
            this.comment = comment;
            this.post = post;
        }

        @Override
        public void run() {
            if (StringUtils.equals(HaloConst.OPTIONS.get(BlogProperties.SMTP_EMAIL_ENABLE.getProp()), TrueFalse.TRUE.getDesc()) && StringUtils.equals(HaloConst.OPTIONS.get(BlogProperties.NEW_COMMENT_NOTICE.getProp()), TrueFalse.TRUE.getDesc())) {
                try {
                    //发送邮件到博主
                    Map<String, Object> map = new HashMap<>();
                    map.put("author", userService.findUser().getUserDisplayName());
                    map.put("pageName", post.getPostTitle());
                    if (StringUtils.equals(post.getPostType(), PostType.POST_TYPE_POST.getDesc())) {
                        map.put("pageUrl", HaloConst.OPTIONS.get(BlogProperties.BLOG_URL.getProp()) + "/archives/" + post.getPostUrl() + "#comment-id-" + comment.getCommentId());
                    } else {
                        map.put("pageUrl", HaloConst.OPTIONS.get(BlogProperties.BLOG_URL.getProp()) + "/p/" + post.getPostUrl() + "#comment-id-" + comment.getCommentId());
                    }
                    map.put("visitor", comment.getCommentAuthor());
                    map.put("commentContent", comment.getCommentContent());
                    mailService.sendTemplateMail(userService.findUser().getUserEmail(), "有新的评论", map, "common/mail/mail_admin.ftl");
                } catch (Exception e) {
                    log.error("邮件服务器未配置：{}", e.getMessage());
                }
            }
        }
    }

    /**
     * 发送邮件给被评论方
     */
    class EmailToParent extends Thread {
        private Comment comment;
        private Comment lastComment;
        private Post post;

        private EmailToParent(Comment comment, Comment lastComment, Post post) {
            this.comment = comment;
            this.lastComment = lastComment;
            this.post = post;
        }

        @Override
        public void run() {
            //发送通知给对方
            if (StringUtils.equals(HaloConst.OPTIONS.get(BlogProperties.SMTP_EMAIL_ENABLE.getProp()), TrueFalse.TRUE.getDesc()) && StringUtils.equals(HaloConst.OPTIONS.get(BlogProperties.NEW_COMMENT_NOTICE.getProp()), TrueFalse.TRUE.getDesc())) {
                if (Validator.isEmail(lastComment.getCommentAuthorEmail())) {
                    Map<String, Object> map = new HashMap<>();
                    map.put("blogTitle", HaloConst.OPTIONS.get(BlogProperties.BLOG_TITLE.getProp()));
                    map.put("commentAuthor", lastComment.getCommentAuthor());
                    map.put("pageName", lastComment.getPost().getPostTitle());
                    if (StringUtils.equals(post.getPostType(), PostType.POST_TYPE_POST.getDesc())) {
                        map.put("pageUrl", HaloConst.OPTIONS.get(BlogProperties.BLOG_URL.getProp()) + "/archives/" + post.getPostUrl() + "#comment-id-" + comment.getCommentId());
                    } else {
                        map.put("pageUrl", HaloConst.OPTIONS.get(BlogProperties.BLOG_URL.getProp()) + "/p/" + post.getPostUrl() + "#comment-id-" + comment.getCommentId());
                    }
                    map.put("commentContent", lastComment.getCommentContent());
                    map.put("replyAuthor", comment.getCommentAuthor());
                    map.put("replyContent", comment.getCommentContent());
                    map.put("blogUrl", HaloConst.OPTIONS.get(BlogProperties.BLOG_URL.getProp()));
                    mailService.sendTemplateMail(
                            lastComment.getCommentAuthorEmail(), "您在" + HaloConst.OPTIONS.get(BlogProperties.BLOG_TITLE.getProp()) + "的评论有了新回复", map, "common/mail/mail_reply.ftl");
                }
            }
        }
    }
}

