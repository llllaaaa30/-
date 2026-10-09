-- 释放分布式锁
-- KEYS[1]: 锁的key
-- ARGV[1]: 锁的value（唯一标识）
-- 返回值: 1表示释放成功，0表示释放失败（锁不存在或不是当前线程持有）

if redis.call('get', KEYS[1]) == ARGV[1] then
    return redis.call('del', KEYS[1])
else
    return 0
end