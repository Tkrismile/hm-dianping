package com.hmdp.service.impl;

import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;
import java.util.Random;

import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
// 日志假设发送验证码
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号,不符合为ture
        if (RegexUtils.isPhoneInvalid(phone)){
            // 如果不符合 返回错误信息
            return Result.fail("手机号格式错误");
        }
        // 符合，生成验证码
        String code = RandomUtil.randomNumbers(6);
        // 保存验证码到session
        session.setAttribute("code", code);
        // 发送验证码
        log.debug("发送验证码成功，验证码：{}",code);

        // 返回ok
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 验证手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)){
            // 如果不符合 返回错误信息
            return Result.fail("手机号格式错误");
        }
        // 验证验证码
        Object cacheCode = session.getAttribute("code");
        String Code = loginForm.getCode();
        if (cacheCode == null || !cacheCode.toString().equals(Code)) {
            // 不一致报错
            return Result.fail(" 验证码错误");
        }
        // 一致，根据手机号查询用户
        User user = query().eq("phone", phone).one();
        // 判断用户是否存在
        if (user == null) {
            // 不存在,创建新用户并保存
            user = createUserWithPhone(phone);
        }
        session.setAttribute("user", user);
        
        return Result.ok();
    }

    private User createUserWithPhone(String phone) {
        // 创建新用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        save(user);
        return user;
    }
}
