package com.hmdp.utils;

public interface ILock {
    // 尝试获取锁
    // @Param tiemoutSec 锁持有的超时时间，过期自动释放
    // @return ture 代表获取锁成功；false代表获取锁失败

    boolean tryLock(long tiemoutSec);

    // 释放锁
    void unlock();
}

