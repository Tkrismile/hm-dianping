package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class RefreshTokenInterceptor implements HandlerInterceptor {

    private StringRedisTemplate stringRedisTemplate;

    // 注意，这里不能使用@Autowired自动注入，因为这个类是自己new的
    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }



    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 基于token后去redis中的用户
        String token = request.getHeader("authorization");

        // 判断token是否存在
        if  (StringUtils.isBlank(token)) {
            // response.setStatus(401);
            // 不需要做拦截
            // return false;
            return true;
        }

        String key = RedisConstants.LOGIN_USER_KEY + token;
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key);
        // 判断用户是否存在
        if  (userMap.isEmpty()) {
            // response.setStatus(401);
            // 不需要做拦截
            // return false;
            return true;
        }
        // 将hash转换成UserDTO对象
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        // 存在，保存用户信息到ThreadLocal
        UserHolder.saveUser(userDTO);

        // 刷新token有效期
        stringRedisTemplate.expire(key,RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        return true;
    }
}
