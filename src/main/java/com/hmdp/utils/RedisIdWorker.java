package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {
    private static final long BEGIN_TIMESTAMP = 1640995200L;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public Long nextId(String prefix) {
        // 1生成时间戳
        long timestamp = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC) - BEGIN_TIMESTAMP;
        // 2生成序列号
        // 2.1获取当前日期，精确到天
        String date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        // 2.2自增长
        long count = stringRedisTemplate.opsForValue().increment("icr:" + prefix + ":" + date);
        // 3生成ID
        return timestamp << 32 | count;
    }

}
//