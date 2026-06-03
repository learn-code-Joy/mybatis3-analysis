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

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;

/**
 * The 2nd level cache transactional buffer.
 * <p>
 * This class holds all cache entries that are to be added to the 2nd level cache during a Session.
 * Entries are sent to the cache when commit is called or discarded if the Session is rolled back.
 * Blocking cache support has been added. Therefore any get() that returns a cache miss
 * will be followed by a put() so any lock associated with the key can be released.
 *
 * <p>TransactionalCache 中的 entriesToAddOnCommit 字段（Map<Object, Object> 类型）用来暂存当前事务中添加到二级缓存中的数据，
 * 这些数据在事务提交时才会真正添加到底层的 Cache 对象（也就是二级缓存）中。
 *
 * <p>那为什么要在事务提交时才将 entriesToAddOnCommit 集合中的缓存数据写入底层真正的二级缓存中，而不是像操作一级缓存那样，每次查询都直接写入缓存呢？其实这是为了防止出现“脏读”。
 *
 * <p>TransactionalCache 中的另一个核心字段是 entriesMissedInCache，它用来记录未命中的 CacheKey 对象。
 * 在事务提交的时候，会将 entriesMissedInCache 集合中的 CacheKey 写入底层的二级缓存（写入时的 Value 为 null）。在事务回滚时，会调用底层二级缓存的 removeObject() 方法，删除 entriesMissedInCache 集合中 CacheKey。
 *
 * <p>你可能会问，为什么要用 entriesMissedInCache 集合记录未命中缓存的 CacheKey 呢？
 * 为什么还要在缓存结束时(commit和rollback)处理这些 CacheKey 呢？这主要是与BlockingCache 装饰器相关。在前面介绍 Cache 时我们提到过，CacheBuilder 默认会添加 BlockingCache 这个装饰器，
 * 而 BlockingCache 的 getObject() 方法会有给 CacheKey 加锁的逻辑，需要在 putObject() 方法或 removeObject() 方法中解锁，否则这个 CacheKey 会被一直锁住，无法使用。
 * @author Clinton Begin
 * @author Eduardo Macarron
 */
public class TransactionalCache implements Cache {

  private static final Log log = LogFactory.getLog(TransactionalCache.class);

  private final Cache delegate;

  /**
   * commit时是否清空（提交后置为false，未提交前为true)
   */
  private boolean clearOnCommit;

  /**
   * 记录当前事务中添加到二级缓存中的数据，这些数据在事务提交时才会真正添加到底层的 Cache 对象（也就是二级缓存）中。
   */
  private final Map<Object, Object> entriesToAddOnCommit;
  /**
   * 记录未命中的 CacheKey 对象
   */
  private final Set<Object> entriesMissedInCache;

  public TransactionalCache(Cache delegate) {
    this.delegate = delegate;
    this.clearOnCommit = false;
    this.entriesToAddOnCommit = new HashMap<>();
    this.entriesMissedInCache = new HashSet<>();
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
  public Object getObject(Object key) {
    // issue #116
    Object object = delegate.getObject(key);
    // 缓存中未命中
    if (object == null) {
      entriesMissedInCache.add(key);
    }
    // issue #146
    // commit前，返回null，防止脏读
    if (clearOnCommit) {
      return null;
    } else {
      // commit后，返回对象
      return object;
    }
  }

  @Override
  public void putObject(Object key, Object object) {
    // 将数据暂存到entriesToAddOnCommit集合
    entriesToAddOnCommit.put(key, object);
  }

  @Override
  public Object removeObject(Object key) {
    return null;
  }

  @Override
  public void clear() {
    clearOnCommit = true;
    entriesToAddOnCommit.clear();
  }

  /**
   * 提交事务
   * 1. 如果clearOnCommit为true，则清空缓存
   * 2. 调用flushPendingEntries()方法，将entriesToAddOnCommit集合中的数据添加到二级缓存（释放BlockingCache的锁）
   * 3. 调用unlockMissedEntries()方法，将entriesMissedInCache集合中的数据添加到二级缓存（释放BlockingCache的锁）
   */
  public void commit() {
    if (clearOnCommit) {
      delegate.clear();
    }
    flushPendingEntries();
    reset();
  }

  public void rollback() {
    // 回滚时，需要调用removeObject()方法，未命中的对象在BlockingCache中会被一直锁住
    // 这个时候如果不调用removeObject()方法，会导致其他线程无法获取到锁，从而该key一直被锁住，无法使用
    unlockMissedEntries();
    reset();
  }

  private void reset() {
    // 这次事务提交完成，重置状态和清空事务相关的集合
    clearOnCommit = false;
    entriesToAddOnCommit.clear();
    entriesMissedInCache.clear();
  }

  private void flushPendingEntries() {
    for (Map.Entry<Object, Object> entry : entriesToAddOnCommit.entrySet()) {
      // 将entriesToAddOnCommit集合中的数据添加到二级缓存
      delegate.putObject(entry.getKey(), entry.getValue());
    }
    for (Object entry : entriesMissedInCache) {
      // miss的key,如果没有添加过,则添加null，这里也是为了调用BlockingCache的putObject方法，释放锁
      if (!entriesToAddOnCommit.containsKey(entry)) {
        delegate.putObject(entry, null);
      }
    }
  }

  private void unlockMissedEntries() {
    // 移除entriesMissedInCache集合中的数据
    for (Object entry : entriesMissedInCache) {
      try {
        // 这里的delegate可能是BlockingCache，需要调用removeObject方法释放锁
        delegate.removeObject(entry);
      } catch (Exception e) {
        log.warn("Unexpected exception while notifiying a rollback to the cache adapter. "
            + "Consider upgrading your cache adapter to the latest version. Cause: " + e);
      }
    }
  }

}
