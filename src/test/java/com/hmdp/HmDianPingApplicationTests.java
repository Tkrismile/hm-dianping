package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private ShopServiceImpl shopService;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void testSaveShop() {
        shopService.saveShop2Redis(1L,10L);
    }

    @Test
    void loadShopData() {
        // 1.查店铺信息
        List<Shop> list = shopService.list();
        // 2.按照typeId分组
        Map<Long,List<Shop>> map = list.stream().collect(Collectors.groupingBy(Shop :: getTypeId));
        // 分析完成存储写入Redis
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            // 获取类型id
            Long typeId = entry.getKey();
            String key = "shop:geo:" + typeId;
            // 获取同类型的店铺的集合
            List<Shop> value = entry.getValue();
            List<RedisGeoCommands.GeoLocation<String>> location =new ArrayList<>(value.size());


            // 写入redis GEOADD key 经度 维度 member
            for (Shop shop : value) {
                // stringRedisTemplate.opsForGeo().add(key, new Point(shop.getX(),shop.getY()),shop.getId().toString());
                location.add(new RedisGeoCommands.GeoLocation<>(
                        shop.getId().toString(),
                        new Point(shop.getX(),shop.getY())
                ));
            }
            stringRedisTemplate.opsForGeo().add(key,location);
        }

    }

    @Test
    void testHyperLogLog() {
        String[] values = new String[1000];
        int j = 0;
        for (int i = 0; i < 1000000; i++) {
            j = i % 1000;
            values[j] = "user_" + i;
            if (j == 999){
                // 发送到redis
                stringRedisTemplate.opsForHyperLogLog().add("hl2", values);
            }
        }
        //统计数量
        Long hl2 = stringRedisTemplate.opsForHyperLogLog().size("hl2");
        System.out.println(hl2);
    }





}


