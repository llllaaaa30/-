-- 秒杀预处理：判断库存并原子扣减
-- KEYS[1]: 库存key (seckill:stock:voucherId)
-- KEYS[2]: 订单key (seckill:order:voucherId:userId)
-- ARGV[1]: userId
-- 返回值: 0=库存不足, 1=已下过单, 2=秒杀成功

local stockKey = KEYS[1]
local orderKey = KEYS[2]
local userId = ARGV[1]

-- 1.判断库存是否充足
local stock = tonumber(redis.call('get', stockKey))
if stock == nil or stock <= 0 then
    return 0
end

-- 2.判断用户是否已下过单
if redis.call('sismember', orderKey, userId) == 1 then
    return 1
end

-- 3.扣减库存
redis.call('incrby', stockKey, -1)

-- 4.记录用户已下单
redis.call('sadd', orderKey, userId)

return 2