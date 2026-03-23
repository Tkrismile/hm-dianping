package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.getter.ListTypeGetter;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IFollowService followService;



    @Override
    public Result getBlogById(Long id) {
        // 1.查询blog
        Blog blog = getById(id);
        if (blog == null){
            return Result.fail("笔记不存在!");
        }
        //2.查询blog有关的用户
        queryBlogUser(blog);
        // 查询blog是否被点赞
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
    private void isBlogLiked(Blog blog) {
        // 1.获取登录用户
        UserDTO user = UserHolder.getUser();
        if (user == null){
            // 用户未登录
            return;
        }
        Long userId = user.getId();
        // 2.判断当登录用户是否已经点赞
        String key = BLOG_LIKED_KEY+ blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户信息查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        //获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            this.queryBlogUser(blog);
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result likeBlog(Long id) {
        // 1.获取登录用户
        Long userId = UserHolder.getUser().getId();
        // 2.判断当登录用户是否已经点赞
        String key = BLOG_LIKED_KEY+ id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score == null ){
            // 3.如果没有点赞，可以点赞
            // 3.1 数据库点赞+1
            boolean issuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            if (issuccess){
                // 3.2 保存用户到redis soctset列表 zadd key value score
                stringRedisTemplate.opsForZSet().add(key, userId.toString(),System.currentTimeMillis());
            }
        }else {
            // 数据库点赞-1
            boolean issuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            // 4.如果已点赞，取消点赞
            if (issuccess){
                // 把用户从redis的set集合移除
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result getBlogLikes(Long id) {
        String key = BLOG_LIKED_KEY+ id;
        //1 查询top5的点赞用户 zrange key 0 4
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (top5 == null || top5.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", ids);
        System.out.println(idStr);
        // 根据用户id查询用户 where id in (5,1) ORDER BY FIELD(id, 5, 1)
        // stream() 的核心是将集合转换为流式处理对象，支持链式的函数式操作,作用类似与for
        // .collect()：终止流并收集结果
        List<UserDTO> userDTOS = userService.query()
                .in("id",ids).last("ORDER BY FIELD(id," + idStr + ")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());

        // 返回
        return Result.ok(userDTOS);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean isSave = save(blog);
        // 查询笔记作者的所有粉丝
        if (!isSave){
            return Result.fail("新增笔记失败！");
        }
        // 查询笔记作者的所有粉丝 select * from tb_follow where follow_user_id == ?
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        for (Follow follow : follows) {
            // 获取粉丝id
            Long userId = follow.getUserId();
            String key = FEED_KEY + userId;
            // 推送笔记id粉丝的收件箱
            stringRedisTemplate.opsForZSet().add(key,blog.getId().toString(),System.currentTimeMillis());

        }

        // 返回id
        return Result.ok(blog.getId());

    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        //1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        String key = FEED_KEY + userId;
        //2.查询收件箱 ZREVRANGEBYSCORE key max Min  LIMIT offset count
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        // 判断
        if (typedTuples == null || typedTuples.isEmpty()){
            return Result.ok();
        }
        //3.解析收件箱：blogId,minTime（时间戳），offset
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0;
        int os = 1; //offset
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) {
            // 获取id
            ids.add(Long.valueOf(tuple.getValue()));
            // 获取时间戳
            long time = tuple.getScore().longValue();
            if (time  == minTime){
                os++;
            }else {
                minTime = time;
                os = 1;
            }
        }
        //4根据id查询blog
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = query().in("id",ids).last("ORDER BY FIELD(id," + idStr + ")").list();

        for (Blog blog : blogs) {
            // 查询blog有关的用户
            queryBlogUser(blog);
            // 查询blog是否被点赞
            isBlogLiked(blog);
        }
        //5，封装返回
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogs);
        scrollResult.setOffset(os);
        scrollResult.setMinTime(minTime);
        return Result.ok(scrollResult);
    }
}
