package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_TTL;

@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 方法1：将任意Java对象序列化为json并存储在string类型的key中，并且可以设置TTL过期时间
     *
     * @param key   Redis key
     * @param value 任意Java对象
     * @param time  过期时间
     * @param unit  时间单位
     */
    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    /**
     * 方法2：将任意Java对象序列化为json并存储在string类型的key中，并且可以设置逻辑过期时间，用于处理缓存击穿问题
     *
     * @param key   Redis key
     * @param value 任意Java对象
     * @param time  逻辑过期时间
     * @param unit  时间单位
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        // 设置逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        // 写入Redis，不设置TTL
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 方法3：根据指定的key查询缓存，并反序列化为指定类型，利用缓存空值的方式解决缓存穿透问题
     *
     * @param keyPrefix  key前缀
     * @param id         查询id
     * @param type       返回类型
     * @param dbFallback 数据库查询函数
     * @param time       缓存过期时间
     * @param unit       时间单位
     * @param <R>        返回类型
     * @param <ID>       id类型
     * @return 查询结果
     */
    public <R, ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type,
            Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 1.从Redis查询缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if (StrUtil.isNotBlank(json)) {
            // 3.存在，直接返回
            return JSONUtil.toBean(json, type);
        }
        // 4.判断命中的是否是空值（缓存穿透防护）
        if (json != null) {
            // 返回一个错误信息或null，这里返回null表示不存在
            return null;
        }
        // 5.不存在，根据id查询数据库
        R r = dbFallback.apply(id);
        // 6.不存在，返回错误，将空值写入Redis
        if (r == null) {
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 7.存在，写入Redis
        this.set(key, r, time, unit);
        // 8.返回
        return r;
    }

    /**
     * 方法3（增强版）：根据指定的key查询缓存，并反序列化为指定类型，利用互斥锁和缓存空值解决缓存击穿和缓存穿透问题
     *
     * @param keyPrefix  key前缀
     * @param id         查询id
     * @param type       返回类型
     * @param dbFallback 数据库查询函数
     * @param time       缓存过期时间
     * @param unit       时间单位
     * @param <R>        返回类型
     * @param <ID>       id类型
     * @return 查询结果
     */
    public <R, ID> R queryWithMutex(
            String keyPrefix, ID id, Class<R> type,
            Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 1.从Redis查询缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if (StrUtil.isNotBlank(json)) {
            // 3.存在，直接返回
            return JSONUtil.toBean(json, type);
        }
        // 4.判断命中的是否是空值（缓存穿透防护）
        if (json != null) {
            return null;
        }
        // 5.实现缓存重建（互斥锁解决缓存击穿）
        // 5.1.获取互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        R r = null;
        try {
            boolean isLock = tryLock(lockKey);
            // 5.2.判断是否获取成功
            if (!isLock) {
                // 5.3.获取锁失败，休眠并重试
                Thread.sleep(50);
                return queryWithMutex(keyPrefix, id, type, dbFallback, time, unit);
            }
            // 5.4.获取锁成功，再次检查缓存（DoubleCheck）
            json = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(json)) {
                return JSONUtil.toBean(json, type);
            }
            // 5.5.仍未命中，查询数据库
            r = dbFallback.apply(id);
            // 6.不存在，将空值写入Redis缓存（缓存穿透防护）
            if (r == null) {
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            // 7.存在，写入Redis缓存
            this.set(key, r, time, unit);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 8.释放互斥锁
            unlock(lockKey);
        }
        // 9.返回查询结果
        return r;
    }

    /**
     * 方法4：根据指定的key查询缓存，并反序列化为指定类型，需要利用逻辑过期解决缓存击穿问题
     *
     * @param keyPrefix  key前缀
     * @param id         查询id
     * @param type       返回类型
     * @param dbFallback 数据库查询函数
     * @param time       逻辑过期时间
     * @param unit       时间单位
     * @param <R>        返回类型
     * @param <ID>       id类型
     * @return 查询结果
     */
    public <R, ID> R queryWithLogicalExpire(
            String keyPrefix, ID id, Class<R> type,
            Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 1.从Redis查询缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if (StrUtil.isBlank(json)) {
            // 3.不存在，直接返回null（需要提前预热缓存）
            return null;
        }
        // 4.命中，需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 5.判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 5.1.未过期，直接返回店铺信息
            return r;
        }
        // 5.2.已过期，需要缓存重建
        // 6.缓存重建
        // 6.1.获取互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        // 6.2.判断是否获取锁成功
        if (isLock) {
            // 6.3.成功，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 查询数据库
                    R r1 = dbFallback.apply(id);
                    // 写入Redis
                    this.setWithLogicalExpire(key, r1, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 释放锁
                    unlock(lockKey);
                }
            });
        }
        // 6.4.返回过期的商铺信息
        return r;
    }

    /**
     * 尝试获取锁
     *
     * @param key 锁的key
     * @return 是否获取成功
     */
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放锁
     *
     * @param key 锁的key
     */
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
}