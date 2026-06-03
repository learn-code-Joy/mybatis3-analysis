/**
 *    Copyright 2009-2025 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.cache.decorators;

import java.text.MessageFormat;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.cache.CacheException;

/**
 * <p>Simple blocking decorator
 *
 * <p>Simple and inefficient version of EhCache's BlockingCache decorator.
 * It sets a lock over a cache key when the element is not found in cache.
 * This way, other threads will wait until this element is filled instead of hitting the database.
 *
 * <p>By its nature, this implementation can cause deadlock when used incorrecly.
 *
 * <p>简单阻塞装饰器
 * EhCache 的阻塞缓存装饰器的一个简单且低效的版本。当在缓存中未找到某个元素时，它会针对该缓存键设置一把锁。通过这种方式，其他线程将等待，直到该元素被填充到缓存中，而不是直接访问数据库。
 * 就其本质而言，这种实现方式在使用不当时可能会导致死锁。
 *
 * <p>对于一个 Key 来说，同一时刻，BlockingCache 只会让一个业务线程到数据库中去查找，查找到结果之后，会添加到 BlockingCache 中缓存。
 *
 * <p>假设业务线程 1、2 并发访问某个 Key，线程 1 查询 delegate 缓存失败，不释放锁，timeout <=0 的时候，线程 2 就会阻塞吗？
 * 是的，但是线程 2 不会永久阻塞，因为我们需要保证线程 1 接下来会查询数据库，并调用 putObject() 方法或 removeObject() 方法，
 * 其中会通过 releaseLock() 方法释放锁。
 *
 * @author Eduardo Macarron
 *
 */
public class BlockingCache implements Cache {

  private long timeout;
  private final Cache delegate;
  private final ConcurrentHashMap<Object, CountDownLatch> locks;

  public BlockingCache(Cache delegate) {
    this.delegate = delegate;
    this.locks = new ConcurrentHashMap<>();
  }

  @Override
  public String getId() {
    return delegate.getId();
  }

  @Override
  public int getSize() {
    return delegate.getSize();
  }

  @Override
  public void putObject(Object key, Object value) {
    try {
      delegate.putObject(key, value);
    } finally {
      // 释放锁
      releaseLock(key);
    }
  }

  @Override
  public Object getObject(Object key) {
    // 获取锁
    acquireLock(key);
    Object value = delegate.getObject(key);
    // 命中缓存之后会立刻调用 releaseLock() 方法释放锁，如果未命中缓存则不会释放锁。
    // 如果没有命中缓存，就需要从数据库里面查询数据，然后调用putObject()方法方法将数据添加到缓存中并释放锁。
    if (value != null) {
      releaseLock(key);
    }
    return value;
  }

  @Override
  public Object removeObject(Object key) {
    // despite of its name, this method is called only to release locks
    releaseLock(key);
    return null;
  }

  @Override
  public void clear() {
    delegate.clear();
  }

  private void acquireLock(Object key) {
    CountDownLatch newLatch = new CountDownLatch(1);
    while (true) {
      // 尝试将key与newLatch这个CountDownLatch对象关联起来
      // 如果没有其他线程并发，则返回的latch为null，否则返回持有锁的线程的CountDownLatch对象
      CountDownLatch latch = locks.putIfAbsent(key, newLatch);
      if (latch == null) {
        // 如果当前key未关联CountDownLatch，
        // 则无其他线程并发，当前线程获取锁成功
        break;
      }
      try {
        // 当前key已关联CountDownLatch对象，则表示有其他线程并发操作当前key，
        // 当前线程需要阻塞在并发线程留下的CountDownLatch对象(latch)之上，
        // 直至并发线程调用latch.countDown()唤醒该线程
        if (timeout > 0) {
          // 根据timeout的值，决定阻塞超时时间
          boolean acquired = latch.await(timeout, TimeUnit.MILLISECONDS);
          // 超时未获取到锁，则抛出异常
          if (!acquired) {
            throw new CacheException(
                "Couldn't get a lock in " + timeout + " for the key " + key + " at the cache " + delegate.getId());
          }
        } else {
          // 死等
          latch.await();
        }
      } catch (InterruptedException e) {
        throw new CacheException("Got interrupted while trying to acquire lock for key " + key, e);
      }
    }
  }

  private void releaseLock(Object key) {
    // 会从 locks 集合中删除 Key 关联的 CountDownLatch 对象
    CountDownLatch latch = locks.remove(key);
    if (latch == null) {
      throw new IllegalStateException("Detected an attempt at releasing unacquired lock. This should never happen.");
    }
    // 唤醒阻塞在这个 CountDownLatch 对象上的业务线程。
    latch.countDown();
  }

  public long getTimeout() {
    return timeout;
  }

  public void setTimeout(long timeout) {
    this.timeout = timeout;
  }
}
