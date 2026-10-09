package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1 校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2 如果不符合，返回错误信息
            return Result.fail("手机号格式错误");
        }
        // 3 符合，生成验证码
        String code = RandomUtil.randomNumbers(6);
        // 4 保存验证码到redis中
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
        // 5 发送验证码到手机号
        log.debug("发送验证码到手机号{}，验证码为{}", phone, code);
        // 6 返回成功信息
        return Result.ok();
    }
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 1 校验手机号
        if (RegexUtils.isPhoneInvalid(loginForm.getPhone())) {
            // 2 如果不符合，返回错误信息
            return Result.fail("手机号格式错误");
        }
        // 3 符合，校验验证码
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + loginForm.getPhone());
        if (cacheCode == null || !cacheCode.equals(loginForm.getCode())) {
            // 4 如果不符合，返回错误信息
            return Result.fail("验证码错误");
        }
        // 5 根据手机号查询用户
        User user = query().eq("phone", loginForm.getPhone()).one();
        // 6 如果用户不存在，创建用户并保存
        if (user == null) {
            user = creatUserWithPhone(loginForm.getPhone());
        }
        // 7 保存用户信息到redis中
        // 7.1 生成一个随机的token（UUID）
        String token = UUID.randomUUID().toString();
        // 7.2 将user对象转换为UserDTO对象，以token为key，并以hash结构保存到redis中
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> usermap = BeanUtil.beanToMap(userDTO,new HashMap<>(),
                CopyOptions.create().setIgnoreNullValue(true).setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token, usermap);
        // 7.3 设置有效期为30分钟
        stringRedisTemplate.expire(LOGIN_USER_KEY + token, 30, TimeUnit.MINUTES);
        // 8 返回成功信息
        return Result.ok(token);
    }

    @Override
    public Result sign() {
        return null;
    }

    @Override
    public Result signCount() {
        return null;
    }

    private User creatUserWithPhone(String phone) {
        // 1 创建用户对象
        User user = new User();
        // 2 设置手机号
        user.setPhone(phone);
        // 3 设置昵称，默认是随机字符
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(8));
        // 4 保存用户
        save(user);
        // 5 返回用户对象
        return user;
    }
}