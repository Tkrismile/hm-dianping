package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {


    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryById(Long id) {
        // 缓存穿透
        // Shop shop = queryWithPassThrough(id);

        // 用互斥锁解决缓存击穿
        Shop shop = queryWithMutex(id);

        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        // 7返回
        return Result.ok(shop);
    }

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unLock(String key) {
        stringRedisTemplate.delete(key);

    }

    public Shop queryWithPassThrough(Long id) {
        // 1从redis查询商品缓存
        String shopRedis = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);

        // 2判断是否存在
        if (StrUtil.isNotBlank(shopRedis)){
            // 3存在直接返回
            return JSONUtil.toBean(shopRedis, Shop.class);
        }
        // 判断是否是空
        if (shopRedis != null) {
            // 返回错误信息
            return null;
        }
        // 4redis不存在，根据id查询数据库
        Shop shop = getById(id);
        if (shop == null) {
            // 将空值写入redis
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            // // 5不存在，返回错误
            return null;
        }
        // 6存在，写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // 7返回
        return shop;
    }

    public Shop queryWithMutex(Long id) {
        // 1从redis查询商品缓存
        String shopRedis = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);

        // 2判断是否存在
        if (StrUtil.isNotBlank(shopRedis)){
            // 3存在直接返回
            return JSONUtil.toBean(shopRedis, Shop.class);
        }
        // 判断是否是空
        if (shopRedis != null) {
            // 返回错误信息
            return null;
        }

        // 实现缓存重建
        // 获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);
            // 判断是否存在
            // 失败，休眠并充实
            if (!isLock){
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            // 成功，根据id查询数据库
            // redis不存在，根据id查询数据库
            shop = getById(id);
            if (shop == null) {
                // 将空值写入redis
                stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                // 返回错误
                return null;
            }
            // 6存在，写入redis
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new  RuntimeException(e);
        } finally {
            //释放互斥锁
            unLock(lockKey);
        }

        // 7返回
        return shop;
    }

    @Override
    @Transactional
    public Result unpdata(Shop shop) {
        Long id = shop.getId();
        if (id == null){
            return  Result.fail("店铺id不能为空");
        }
        //1  更新数据库
        updateById(shop);
        //2 删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shop.getId());

        return Result.ok();
    }


}
