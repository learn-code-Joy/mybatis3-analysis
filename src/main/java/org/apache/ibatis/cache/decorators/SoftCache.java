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

import java.lang.ref.ReferenceQueue;
import java.lang.ref.SoftReference;
import java.util.Deque;
import java.util.LinkedList;

import org.apache.ibatis.cache.Cache;

/**
 * Soft Reference cache decorator
 * Thanks to Dr. Heinz Kabutz for his guidance here.
 *
 * <p>这个 Person 对象被强引用的时候，即使是 JVM 内存空间不足触发 GC，
 *   甚至是内存溢出（OutOfMemoryError），也不会回收这个 Person 对象。
 *
 *  <p>软引用比强引用稍微弱一些。当 JVM 内存不足时，
 *   GC 才会回收那些只被软引用指向的对象，从而避免 OutOfMemoryError。
 *   当 GC 将只被软引用指向的对象全部回收之后，内存依然不足时，JVM 才会抛出 OutOfMemoryError。
 *   软引用特别适合做缓存，因为缓存中的数据可以从数据库中恢复，所以即使因为 JVM 内存不足而被回收掉，也可以通过数据库恢复缓存中的对象。
 *   <p>
 *     在使用软引用的时候，需要注意一点：当拿到一个软引用的时候，我们需要先判断其 get() 方法返回值是否为 null。
 *     如果为 null，则表示这个软引用指向的对象在之前的某个时刻，已经被 GC 掉了；如果不为 null，则表示这个软引用指向的对象还存活着。
 *   <p>在有的场景中，我们可能需要在一个对象的可达性（是否已经被回收）发生变化时，得到相应的通知，引用队列（Reference Queue） 就是用来实现这个需求的。
 *   当一个对象被 GC 掉之后，这个对象所对应的软引用就会被加入到引用队列中，从而我们可以在适当的时候得到通知。
 * @author Clinton Begin
 */
public class SoftCache implements Cache {

  /**
   * 在 SoftCache 中，最近经常使用的一部分缓存条目（也就是热点数据）会被添加到这个集合中，
   * 正如其名称的含义，该集合会使用强引用指向其中的每个缓存 Value，防止它被 GC 回收。
   */
  private final Deque<Object> hardLinksToAvoidGarbageCollection;

  /**
   * 该引用队列会与每个 SoftEntry 对象关联，用于记录已经被回收的缓存条目，即 SoftEntry 对象，
   * SoftEntry 又通过 key 这个强引用指向缓存的 Key 值，这样我们就可以知道哪个 Key 被回收了。
   */
  private final ReferenceQueue<Object> queueOfGarbageCollectedEntries;

  /**
   * SoftCache 装饰的底层 Cache 对象。
   */
  private final Cache delegate;

  /**
   * 指定了强连接的个数，默认值是 256，也就是最近访问的 256 个 Value 无法直接被 GC 回收。
   */
  private int numberOfHardLinks;

  public SoftCache(Cache delegate) {
    this.delegate = delegate;
    this.numberOfHardLinks = 256;
    this.hardLinksToAvoidGarbageCollection = new LinkedList<>();
    this.queueOfGarbageCollectedEntries = new ReferenceQueue<>();
  }

  @Override
  public String getId() {
    return delegate.getId();
  }

  @Override
  public int getSize() {
    // 可能会删除key，所以需要先执行删除，然后再获取size
    removeGarbageCollectedItems();
    return delegate.getSize();
  }

  public void setSize(int size) {
    this.numberOfHardLinks = size;
  }

  @Override
  public void putObject(Object key, Object value) {
    removeGarbageCollectedItems();
    delegate.putObject(key, new SoftEntry(key, value, queueOfGarbageCollectedEntries));
  }

  @Override
  public Object getObject(Object key) {
    Object result = null;
    @SuppressWarnings("unchecked") // assumed delegate cache is totally managed by this cache
    // 从底层被装饰的缓存中查找数据
    SoftReference<Object> softReference = (SoftReference<Object>) delegate.getObject(key);
    if (softReference != null) {
      result = softReference.get();
      if (result == null) {
        // Value为null，则已被GC回收，直接从缓存删除该Key
        delegate.removeObject(key);
      } else {
        // See #586 (and #335) modifications need more than a read lock
        // 将Value添加到hardLinksToAvoidGarbageCollection集合中，
        // 防止被GC回收(强引用,这样delegate里面的value不会被GC回收)
        synchronized (hardLinksToAvoidGarbageCollection) {
          hardLinksToAvoidGarbageCollection.addFirst(result);
          // 检查hardLinksToAvoidGarbageCollection长度，超过上限，则清理最早添加的Value
          if (hardLinksToAvoidGarbageCollection.size() > numberOfHardLinks) {
            hardLinksToAvoidGarbageCollection.removeLast();
          }
        }
      }
    }
    return result;
  }

  @Override
  public Object removeObject(Object key) {
    removeGarbageCollectedItems();
    return delegate.removeObject(key);
  }

  @Override
  public void clear() {
    synchronized (hardLinksToAvoidGarbageCollection) {
      hardLinksToAvoidGarbageCollection.clear();
    }
    removeGarbageCollectedItems();
    delegate.clear();
  }

  private void removeGarbageCollectedItems() {
    SoftEntry sv;
    // 遍历queueOfGarbageCollectedEntries集合，其中记录了被GC回收的Key
    while ((sv = (SoftEntry) queueOfGarbageCollectedEntries.poll()) != null) {
      delegate.removeObject(sv.key);
    }
  }

  /**
   * 其中指向 key 的引用是强引用，指向 value 的引用是软引用
   */
  private static class SoftEntry extends SoftReference<Object> {
    private final Object key;

    SoftEntry(Object key, Object value, ReferenceQueue<Object> garbageCollectionQueue) {
      // 指向value的是软引用，并且关联了引用队列
      super(value, garbageCollectionQueue);
      // 指向key的是强引用
      this.key = key;
    }
  }

}
