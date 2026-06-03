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

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.ibatis.cache.Cache;

/**
 * Lru (least recently used) cache decorator.
 *
 * <p>清除最近最少使用的缓存条目。
 * @author Clinton Begin
 */
public class LruCache implements Cache {

  private final Cache delegate;
  /**
   * 用来记录各个缓存条目最近的使用情况
   */
  private Map<Object, Object> keyMap;

  /**
   * 用来指向最近最少使用的 Key
   */
  private Object eldestKey;

  public LruCache(Cache delegate) {
    this.delegate = delegate;
    setSize(1024);
  }

  @Override
  public String getId() {
    return delegate.getId();
  }

  @Override
  public int getSize() {
    return delegate.getSize();
  }

  public void setSize(final int size) {
    keyMap = new LinkedHashMap<Object, Object>(size, .75F, true) {
      /**
       * 当我们调用 get() 方法访问 Key 4 时，LinkedHashMap 除了返回 Value 4 之外，
       * 还会默默修改 Entry 链表，将 Key 4 项移动到链表的尾部。越靠近尾部，越是最近访问的对象
       */
      private static final long serialVersionUID = 4267176411845948333L;

      /*
      * removeEldestEntry() 方法实现，当 LruCache 中缓存条目达到上限的时候，
      * 返回 true，即删除 Entry 链表中 head 指向的 Entry。
      * 调用LinkedHashMap.put()方法时，会调用removeEldestEntry()方法，
      * 决定是否删除head指向的Entry数据
       */
      @Override
      protected boolean removeEldestEntry(Map.Entry<Object, Object> eldest) {
        boolean tooBig = size() > size;
        if (tooBig) {
          eldestKey = eldest.getKey();
        }
        return tooBig;
      }
    };
  }

  @Override
  public void putObject(Object key, Object value) {
    delegate.putObject(key, value);
    cycleKeyList(key);
  }

  @Override
  public Object getObject(Object key) {
    keyMap.get(key); // 访问元素(会改变keyMap中key的顺序)
    return delegate.getObject(key);
  }

  @Override
  public Object removeObject(Object key) {
    return delegate.removeObject(key);
  }

  @Override
  public void clear() {
    delegate.clear();
    keyMap.clear();
  }

  private void cycleKeyList(Object key) {
    // 如果 eldestKey 不为空，说明缓存条目已经达到上限，
    // 我们需要删除cache中的key，然后将eldestKey设置为null，
    keyMap.put(key, key);
    if (eldestKey != null) {
      delegate.removeObject(eldestKey);
      eldestKey = null;
    }
  }

}
