package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class LoginInterceptor implements HandlerInterceptor {

//    private StringRedisTemplate stringRedisTemplate;
//
//    // 注意，这里不能使用@Autowired自动注入，因为这个类是自己new的
//    public LoginInterceptor(StringRedisTemplate stringRedisTemplate) {
//        this.stringRedisTemplate = stringRedisTemplate;
//    }

    // 前置
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
//        // 1.get session
//        // HttpSession session = request.getSession();
//        // 获取请求头中的token
//
//        // get session user
//        // Object user = session.getAttribute("user");
//
//        // 基于token后去redis中的用户
//        String token = request.getHeader("authorization");
//
//        // 判断token是否存在
//        if  (StringUtils.isBlank(token)) {
//            response.setStatus(401);
//            return false;
//        }
//        String key = RedisConstants.LOGIN_USER_KEY + token;
//        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key);
//        // 判断用户是否存在
//        if  (userMap.isEmpty()) {
//            response.setStatus(401);
//            return false;
//        }
//        // 将hash转换成UserDTO对象
//        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
//        // 存在，保存用户信息到ThreadLocal
//
//        UserHolder.saveUser(userDTO);
//        // 刷新token有效期
//        stringRedisTemplate.expire(key,RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // 放行

        // 判断时候需要拦截（TreadLocal中是否存在用户）
        if (UserHolder.getUser() == null) {
            response.setStatus(401);
            return false;
        }
        return true;
    }


//    // 后置
//    @Override
//    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
//        UserHolder.removeUser();
//    }

}
