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

import static com.hmdp.utils.RedisConstants.*;

@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;


    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit){

        RedisData redisData = new RedisData();
        redisData.setData(JSONUtil.toJsonStr(value));
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));

        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value));
    }


    public <R,ID> R queryWithPassThrough(
            String KeyPrefix, ID id,Class<R> type, Function<ID,R> dbFallback, Long time, TimeUnit unit) {

        String key = KeyPrefix + id;
        // 1从redis查询商品缓存
        String json = stringRedisTemplate.opsForValue().get(key);

        // 2判断是否存在
        if (StrUtil.isNotBlank(json)){
            // 3存在直接返回
            return JSONUtil.toBean(json, type);
        }
        // 判断是否是空
        if (json != null) {
            // 返回错误信息
            return null;
        }
        // 4redis不存在，根据id查询数据库
        R r = dbFallback.apply(id);
        if (r == null) {
            // 将空值写入redis
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            // // 5不存在，返回错误
            return null;
        }
        // 6存在，写入redis
        this.set(key, r, time, unit);
        // 7返回
        return r;
    }

    public <R,ID> R queryWithMutex(
            String KeyPrefix, ID id, Class<R> type, Function<ID,R> dbFallback, Long time, TimeUnit unit) {

        String key = KeyPrefix + id;

        // 1从redis查询商品缓存
        String json = stringRedisTemplate.opsForValue().get(key);

        // 2判断是否存在
        if (StrUtil.isNotBlank(json)){
            // 3存在直接返回
            return JSONUtil.toBean(json, type);
        }
        // 判断是否是空
        if (json != null) {
            // 返回错误信息
            return null;
        }

        // 实现缓存重建
        // 获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        R r = null;
        try {
            boolean isLock = tryLock(lockKey);
            // 判断是否存在
            // 失败，休眠并充实
            if (!isLock){
                Thread.sleep(50);
                return queryWithMutex(KeyPrefix, id, type,  dbFallback, time, unit);
            }
            // 成功，根据id查询数据库
            // redis不存在，根据id查询数据库
            r = dbFallback.apply(id);
            if (r == null) {
                // 将空值写入redis
                stringRedisTemplate.opsForValue().set(key, "", time, unit);
                // 返回错误
                return null;
            }
            // 6存在，写入redis
            this.set(key, r, time, unit);
        } catch (InterruptedException e) {
            throw new  RuntimeException(e);
        } finally {
            //释放互斥锁
            unLock(lockKey);
        }

        // 7返回
        return r;
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public <R,ID> R queryWithLogicalExpire(
            String KeyPrefix,ID id, Class<R> type,Function<ID,R> dbFallback,Long time, TimeUnit unit) {

        String key = KeyPrefix + id;
        // 1从redis查询商品缓存
        String json = stringRedisTemplate.opsForValue().get(key);

        // 2判断是否存在
        if (StrUtil.isBlank(json)){
            // 3为命中直接返回
            return null;
        }

        // 命中，需要先把json反序列化
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();

        //判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            //未过期，返回店铺信息
            return  r;

        }
        //过期，需要缓存重建
        // 获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        //判断是否获取锁成功
        if (isLock){
            //成功，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 查询数据库
                    R r1 = dbFallback.apply(id);
                    //写入redis
                    this.setWithLogicalExpire(key, r1, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    // 释放锁
                    unLock(lockKey);
                }
            });
        }

        //失败，返回过期的商品信息
        return r;
    }

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unLock(String key) {
        stringRedisTemplate.delete(key);

    }


}
