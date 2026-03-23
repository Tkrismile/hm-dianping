package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IUserService userService;

    @Override
    public Result isFollow(Long followUserId) {
        // 获取关注用户的id
        Long UserId = UserHolder.getUser().getId();
        // 查询是否关注 select count(*) from tb_follow where userId = ? and follow_user_id = ?
        Integer count = Math.toIntExact(query().eq("user_id", UserId).eq("follow_user_id", followUserId).count());
        return Result.ok(count >0);
    }

    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        // 获取关注用户的id
        Long UserId = UserHolder.getUser().getId();

        String key = "fllows:" + UserId;
        // 判断关注还是取关
        if (isFollow) {
            //1 关注，新增数据
            Follow follow = new Follow();
            follow.setFollowUserId(followUserId);
            follow.setUserId(UserId);
            boolean isSuccess = save(follow);
            if  (isSuccess) {
                // 注入redis,保存用户关注了谁
                stringRedisTemplate.opsForSet().add(key, followUserId.toString());
            }
        }else{
            //2 取关；删除数据 delete from tb_follow where userId = ? and follow_user_id = ?
            // QueryWrapper：MyBatis-Plus 提供的条件构造器类，专门用来构建 SQL 的 WHERE 条件（支持 eq/ne/gt/lt/like 等几乎所有 SQL 条件）
            // <Follow>：泛型，指定这个构造器是针对 Follow 实体类的（对应数据库中的 follow 表），主要作用是类型提示 / 校验，避免拼错表字段（部分场景也能结合实体字段映射）；
            boolean isSuccess = remove(new QueryWrapper<Follow>().eq("user_id", UserId).eq("follow_user_id", followUserId));
            if (isSuccess) {
                // redis中把用户id从redis集合中移除
                stringRedisTemplate.opsForSet().remove(key, followUserId.toString());

            }

        }

        return Result.ok();
    }

    @Override
    public Result followCommons(Long followUserId) {
        // 当前用户
        Long UserId = UserHolder.getUser().getId();
        String key = "fllows:" + UserId;
        // 求交集
        String key2 = "fllows:" + followUserId;
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key, key2);
        if  (intersect == null || intersect.isEmpty()) {
            // 无交集
            return Result.ok(Collections.emptyList());
        }
        // 解析出id
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        // 查询id
        List<UserDTO> users = userService.listByIds(ids)
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());

        return Result.ok(users);
    }
}
