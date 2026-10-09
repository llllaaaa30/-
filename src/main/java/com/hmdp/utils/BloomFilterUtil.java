package com.hmdp.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <h2>分布式布隆过滤器工具类</h2>
 * <p>
 * 基于 Redis 原生 String 位图（Bitmap）实现，使用 setBit/getBit API 操作。
 * <b>不依赖 RedisBloom 插件</b>，仅使用 Redis 内置命令。
 * </p>
 *
 * <h3>核心原理</h3>
 * <ol>
 *   <li>使用一个大的 bit 位图（Redis String 底层是 byte 数组，可看作 bit 数组）</li>
 *   <li>对一个元素（店铺ID）执行 k 个不同的 hash 函数，计算 k 个 bit 下标</li>
 *   <li>add()：将 k 个 bit 下标全部置为 1</li>
 *   <li>mightContain()：检查 k 个 bit 下标是否全部为 1</li>
 *   <li>任意一个 bit 为 0 → 元素<b>一定不存在</b>（无假阴性）</li>
 *   <li>全部 bit 为 1 → 元素<b>可能存在</b>（存在假阳性/误判）</li>
 * </ol>
 *
 * <h3>参数说明</h3>
 * <pre>
 * 预估元素数量 n = 10000（店铺数量，可按实际扩展）
 * 目标误判率  p = 0.01（1%）
 * Bitmap大小   m = -n * ln(p) / (ln2)^2
 *                = -10000 * (-4.605) / (0.6931)^2
 *                ≈ 95850 bits ≈ 12KB
 * Hash函数数量 k = (m/n) * ln(2)
 *                = (95850/10000) * 0.6931
 *                ≈ 7
 * </pre>
 *
 * <h3>已知缺陷</h3>
 * <ul>
 *   <li><b>不能删除元素</b>：普通 Bitmap 布隆过滤器不支持删除。
 *       如果需要删除店铺，对应的 bit 位不能简单置0（会影响其他元素）。
 *       折中方案：店铺删除接口暂不处理布隆删除，假阳性误判由缓存空值兜底。</li>
 *   <li><b>存在假阳性（误判）</b>：布隆返回"可能存在"时，有 1% 概率该店铺实际不存在。
 *       误判的请求会走到缓存+数据库，查询不到后写入 Redis 空值兜底。</li>
 *   <li><b>容量固定</b>：bitmap 大小在初始化时确定，扩展需要重建布隆。</li>
 * </ul>
 *
 * <h3>与 RedisBloom 插件版的区别</h3>
 * <ul>
 *   <li>本实现：使用 Redis 原生 String + setBit/getBit 命令，无需安装额外模块</li>
 *   <li>RedisBloom 插件版：使用 BF.ADD / BF.EXISTS 命令，支持更多高级特性（可扩展、可删除等）</li>
 * </ul>
 *
 * @author 基于黑马点评项目改造
 * @since 2026-10-01
 */
@Slf4j
@Component
public class BloomFilterUtil {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 向布隆过滤器中添加一个元素
     * <p>
     * 将元素通过 k 个 hash 函数映射到 k 个 bit 下标，全部置为 1。
     * 使用 Redis SETBIT key offset 1 命令。
     * </p>
     *
     * @param value 要添加的元素值（店铺ID的字符串形式）
     */
    public void add(String value) {
        // 获取 k 个 hash 计算出的 bit 下标
        long[] indexes = getHashIndexes(value);
        // 通过 pipeline 批量设置 bit 位，减少网络往返
        stringRedisTemplate.executePipelined((org.springframework.data.redis.connection.RedisConnection connection) -> {
            for (long index : indexes) {
                connection.setBit(
                        BLOOM_SHOP_KEY.getBytes(StandardCharsets.UTF_8),
                        index,
                        true
                );
            }
            return null;
        });
        log.debug("布隆过滤器添加元素: key={}, value={}", BLOOM_SHOP_KEY, value);
    }

    /**
     * 判断元素是否可能存在于布隆过滤器中
     * <p>
     * 将元素通过 k 个 hash 函数映射到 k 个 bit 下标，逐一检查：
     * <ul>
     *   <li>任意一个 bit 为 0 → 元素<b>一定不存在</b>，返回 false</li>
     *   <li>所有 bit 都为 1 → 元素<b>可能存在</b>，返回 true</li>
     * </ul>
     * 使用 Redis GETBIT key offset 命令。
     * </p>
     *
     * @param value 要检查的元素值（店铺ID的字符串形式）
     * @return true=可能存在（需要进一步查缓存/DB），false=一定不存在（直接返回）
     */
    public boolean mightContain(String value) {
        // 获取 k 个 hash 计算出的 bit 下标
        long[] indexes = getHashIndexes(value);
        // 逐一检查每个 bit 位
        for (long index : indexes) {
            Boolean bit = stringRedisTemplate.opsForValue().getBit(BLOOM_SHOP_KEY, index);
            // 任意一个 bit 为 0（注意：getBit 返回 false 表示该位为 0）
            if (Boolean.FALSE.equals(bit)) {
                log.debug("布隆过滤器判定不存在: key={}, value={}, index={}", BLOOM_SHOP_KEY, value, index);
                return false;
            }
        }
        // 所有 bit 都为 1，元素可能存在
        log.debug("布隆过滤器判定可能存在: key={}, value={}", BLOOM_SHOP_KEY, value);
        return true;
    }

    /**
     * 计算元素在布隆过滤器中的 k 个 bit 下标
     * <p>
     * 采用"双重哈希组合"策略生成 k 个哈希值：
     * 先计算两个基础哈希值 hash1、hash2（使用不同的种子），
     * 然后通过公式 hash = hash1 + i * hash2 组合出 k 个哈希值，
     * 最后对 bitmap 容量取模得到 bit 下标。
     * </p>
     *
     * @param value 元素值（店铺ID）
     * @return k 个 bit 下标数组
     */
    private long[] getHashIndexes(String value) {
        long[] indexes = new long[BLOOM_HASH_COUNT];
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);

        // 使用不同种子计算两个基础哈希值（种子选自不同的大质数，保证hash分散性）
        long hash1 = murmurStyleHash(bytes, 0x9e3779b9L);
        long hash2 = murmurStyleHash(bytes, 0x517cc1b7L);

        // 组合生成 k 个哈希值，并映射到 bitmap 下标
        for (int i = 0; i < BLOOM_HASH_COUNT; i++) {
            // hash = hash1 + i * hash2，防止溢出取绝对值
            long combinedHash = hash1 + (long) i * hash2;
            if (combinedHash < 0) {
                combinedHash = ~combinedHash;
            }
            indexes[i] = combinedHash % BLOOM_BITMAP_SIZE;
        }

        return indexes;
    }

    /**
     * 类 MurmurHash 风格的哈希函数
     * <p>
     * 参考 MurmurHash64A 的实现思路，使用乘法和异或操作进行混淆，
     * 确保 hash 值在整个 long 范围内均匀分布。
     * </p>
     *
     * @param data 输入字节数组
     * @param seed 哈希种子（不同种子产生不同哈希值）
     * @return 64位哈希值（非负数）
     */
    private long murmurStyleHash(byte[] data, long seed) {
        // MurmurHash64A 使用的魔数常量
        final long m = 0xc6a4a7935bd1e995L;
        final int r = 47;

        long h = seed ^ (data.length * m);

        int length = data.length;
        // 每次处理 8 个字节（64位）
        int offset = 0;
        while (length >= 8) {
            long k = getLittleEndianLong(data, offset);
            k *= m;
            k ^= k >>> r;
            k *= m;
            h ^= k;
            h *= m;
            offset += 8;
            length -= 8;
        }

        // 处理剩余不足 8 字节的尾部数据
        if (length > 0) {
            long remaining = 0;
            for (int i = 0; i < length; i++) {
                remaining |= ((long) (data[offset + i] & 0xFF)) << (i * 8);
            }
            h ^= remaining;
            h *= m;
        }

        // 最终混淆
        h ^= h >>> r;
        h *= m;
        h ^= h >>> r;

        // 确保返回非负数
        return h < 0 ? ~h : h;
    }

    /**
     * 从字节数组中按小端序读取一个 long 值
     *
     * @param data   字节数组
     * @param offset 起始偏移量
     * @return 64位 long 值
     */
    private long getLittleEndianLong(byte[] data, int offset) {
        long result = 0;
        for (int i = 7; i >= 0; i--) {
            result = (result << 8) | (data[offset + i] & 0xFF);
        }
        return result;
    }
}