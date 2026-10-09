package com.hmdp.utils;

public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final Long LOGIN_USER_TTL = 36000L;

    public static final Long CACHE_NULL_TTL = 2L;

    public static final Long CACHE_SHOP_TTL = 30L;
    public static final String CACHE_SHOP_KEY = "cache:shop:";

    public static final Long CACHE_SHOP_TYPE_TTL = 1440L;
    public static final String CACHE_SHOP_TYPE_KEY = "cache:shop-type:";

    public static final String LOCK_SHOP_KEY = "lock:shop:";
    public static final Long LOCK_SHOP_TTL = 10L;

    public static final String LOCK_ORDER_KEY = "lock:order:";
    public static final Long LOCK_ORDER_TTL = 10L;
    public static final String FOLLOW_KEY = "follows:";
    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    public static final String SECKILL_ORDER_KEY = "seckill:order:";
    public static final String SECKILL_STREAM_KEY = "stream:order";
    public static final String SECKILL_STREAM_GROUP = "g1";
    public static final String BLOG_LIKED_KEY = "blog:liked:";
    public static final String FEED_KEY = "feed:";
    public static final String SHOP_GEO_KEY = "shop:geo:";
    public static final String USER_SIGN_KEY = "sign:";

    /**
     * 布隆过滤器 —— 店铺ID
     * key: bloom:shop
     * 数据结构: Redis String 底层 bit 位图（Bitmap）
     * 预估元素数量: 10000家店铺
     * 误判率: 1%
     * Bitmap容量: m = -n*ln(p) / (ln2)^2 = -10000*ln(0.01)/(0.6931)^2 ≈ 95850 bits ≈ 12KB
     * Hash函数数量: k = (m/n)*ln2 = (95850/10000)*0.6931 ≈ 7
     */
    public static final String BLOOM_SHOP_KEY = "bloom:shop";
    /**
     * 布隆过滤器预估元素数量
     */
    public static final long BLOOM_EXPECTED_INSERTIONS = 10000L;
    /**
     * 布隆过滤器误判率
     */
    public static final double BLOOM_FPP = 0.01;
    /**
     * 布隆过滤器bitmap容量
     */
    public static final long BLOOM_BITMAP_SIZE = 95850L;
    /**
     * 布隆过滤器hash函数数量
     */
    public static final int BLOOM_HASH_COUNT = 7;
}