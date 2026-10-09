package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.*;

import static com.hmdp.utils.RedisConstants.*;

/**
 * 优惠券订单服务实现类
 * 核心业务：秒杀下单
 * 架构：Redis Lua原子预判 + RocketMQ消息队列异步下单 + 分布式锁 + 数据库事务
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    private static final String SECKILL_ORDER_TOPIC = "seckill-order-topic";

    // 秒杀预判Lua脚本：原子性地完成库存判断、一人一单判断、扣减库存
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    // 分布式锁释放Lua脚本：保证只释放自己加的锁，防止误删别人的锁
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    // 静态代码块：在类加载时初始化两个Lua脚本
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setResultType(Long.class);
        SECKILL_SCRIPT.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/seckill.lua")));

        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setResultType(Long.class);
        UNLOCK_SCRIPT.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/unlock.lua")));
    }

    // 秒杀券服务，用于扣减数据库中的库存
    @Resource
    private ISeckillVoucherService seckillVoucherService;

    // Redis全局唯一ID生成器，用于生成订单ID
    @Resource
    private RedisIdWorker redisIdWorker;

    // Redis操作模板，用于执行Lua脚本、分布式锁等
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    // RocketMQ消息队列操作模板，用于发送异步下单消息
    @Resource
    private RocketMQTemplate rocketMQTemplate;

    // 当前Service的代理对象，用于调用createVoucherOrderWithTx()使@Transactional事务生效
    // @Lazy延迟加载，解决Service自己注入自己的循环依赖问题
    @Lazy
    @Resource
    private IVoucherOrderService voucherOrderService;

    /**
     * 秒杀下单入口方法（由Controller调用，主线程同步执行）
     * 核心思路：Redis做轻量预判 → RocketMQ消息队列异步落库 → 快速返回
     *
     * @param voucherId 优惠券ID
     * @return 下单结果，成功返回订单ID
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        // 从ThreadLocal中获取当前登录用户ID
        Long userId = UserHolder.getUser().getId();

        // 1. 执行Lua脚本：原子性地完成以下4步操作（避免并发超卖和重复下单）
        //    ① 判断库存是否充足
        //    ② 判断用户是否已下过单（一人一单）
        //    ③ 扣减Redis中的库存
        //    ④ 记录用户已下单
        //    返回值：0=库存不足，1=已下过单，2=预判成功
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Arrays.asList(SECKILL_STOCK_KEY + voucherId, SECKILL_ORDER_KEY + voucherId),
                userId.toString()
        );
        int r = result.intValue();
        if (r != 2) {
            // Lua脚本返回非2，秒杀预判失败，直接返回错误
            return Result.fail(r == 0 ? "库存不足" : "每人限购一单");
        }

        // 2. Lua脚本预判成功，构造订单对象
        VoucherOrder voucherOrder = new VoucherOrder();
        // 使用Redis全局唯一ID生成器生成订单ID（雪花算法）
        Long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);

        // 将订单对象发送到RocketMQ消息队列，异步消费者会取出并完成数据库下单
        // syncSend：同步发送，保证消息一定到达Broker后才返回
        rocketMQTemplate.syncSend(SECKILL_ORDER_TOPIC, voucherOrder);

        // 3. 异步下单：主线程不等待数据库操作完成，直接返回订单ID给前端
        return Result.ok(orderId);
    }

    /**
     * 带分布式锁的下单方法（由RocketMQ消费者SeckillOrderConsumer调用）
     * 使用Redis分布式锁保证同一用户同一优惠券不会并发重复下单
     * 锁粒度：userId + voucherId，不同用户/不同优惠券互不影响
     *
     * @param voucherOrder 订单信息
     */
    @Override
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();

        // 构造分布式锁的Key：lock:order:{userId}:{voucherId}
        // 锁Value使用UUID，用于安全释放锁时验证是否是自己的锁
        String lockKey = LOCK_ORDER_KEY + userId + ":" + voucherId;
        String lockValue = UUID.randomUUID().toString();

        // 尝试获取分布式锁：SETNX + 过期时间（SET lockKey lockValue EX 10 NX）
        // 如果锁已存在（其他线程持有），获取失败
        Boolean isLock = stringRedisTemplate.opsForValue()
                .setIfAbsent(lockKey, lockValue, LOCK_ORDER_TTL, java.util.concurrent.TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(isLock)) {
            log.error("获取分布式锁失败，userId={}, voucherId={}", userId, voucherId);
            return;
        }
        try {
            // 获取锁成功，通过代理对象调用事务方法
            // 必须通过voucherOrderService调用，而不是this，否则@Transactional事务不生效
            voucherOrderService.createVoucherOrderWithTx(voucherOrder);
        } finally {
            // 释放分布式锁：使用Lua脚本保证"判断锁值+删除锁"的原子性
            // 防止误删别人的锁：先判断锁的值是否等于自己的UUID，是才删除
            stringRedisTemplate.execute(UNLOCK_SCRIPT,
                    Collections.singletonList(lockKey),
                    lockValue);
        }
    }

    /**
     * 事务下单方法：在数据库事务中完成最终下单
     * 包含三步操作：一人一单校验 → 扣减库存 → 保存订单
     * 任何一步失败，整个事务回滚
     *
     * 注意：必须通过Spring代理对象调用（voucherOrderService.createVoucherOrderWithTx），
     * 而不是this直接调用，否则@Transactional注解不生效（AOP代理问题）
     *
     * @param voucherOrder 订单信息
     */
    @Override
    @Transactional
    public void createVoucherOrderWithTx(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();

        // 1. 数据库层面二次校验一人一单（Redis预判可能有延迟，数据库校验是最终兜底）
        Integer count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            log.error("一人一单校验失败，userId={}, voucherId={}", userId, voucherId);
            return;
        }

        // 2. 扣减数据库库存（乐观锁方式：WHERE stock > 0，防止超卖）
        // 对应SQL：UPDATE seckill_voucher SET stock = stock - 1 WHERE voucher_id = ? AND stock > 0
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();
        if (!success) {
            log.error("扣减库存失败，voucherId={}", voucherId);
            return;
        }

        // 3. 保存订单到数据库
        save(voucherOrder);
    }
}