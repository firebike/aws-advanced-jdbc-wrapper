/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package software.amazon.jdbc.util;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

public class SlidingExpirationCache<K, V> {
  private final Map<K, CacheItem> cache = new ConcurrentHashMap<>();
  private long cleanupIntervalNanos = TimeUnit.MINUTES.toNanos(10);
  private final AtomicLong cleanupTimeNanos = new AtomicLong(System.nanoTime() + cleanupIntervalNanos);
  private final ShouldDisposeFunc<V> shouldDisposeFunc;
  private final ItemDisposalFunc<V> itemDisposalFunc;

  /**
   * A cache that periodically cleans up expired entries. Fetching an expired entry marks that entry
   * as not-expired and renews its expiration time.
   */
  public SlidingExpirationCache() {
    this.shouldDisposeFunc = null;
    this.itemDisposalFunc = null;
  }

  /**
   * A cache that periodically cleans up expired entries. Fetching an expired entry marks that entry
   * as not-expired and renews its expiration time.
   *
   * @param shouldDisposeFunc a function defining the conditions under which an expired entry should
   *                          be cleaned up when we hit the cleanup time
   * @param itemDisposalFunc  a function that will be called on any item that meets the cleanup
   *                          criteria at cleanup time. The criteria for cleanup is that the item
   *                          is both expired and marked for cleanup via a call to
   *                          shouldDisposeFunc.
   */
  public SlidingExpirationCache(
      final ShouldDisposeFunc<V> shouldDisposeFunc,
      final ItemDisposalFunc<V> itemDisposalFunc) {
    this.shouldDisposeFunc = shouldDisposeFunc;
    this.itemDisposalFunc = itemDisposalFunc;
  }

  /**
   * In addition to performing the logic defined by {@link Map#computeIfAbsent}, cleans up expired
   * entries if we have hit cleanup time. If an expired entry is requested and we have not hit
   * cleanup time or {@link ShouldDisposeFunc} indicated the entry should not be closed, the entry
   * will be marked as non-expired.
   *
   * @param key                the key with which the specified value is to be associated
   * @param mappingFunction    the function to compute a value
   * @param itemExpirationNano the expiration time of the new or renewed entry
   * @return the current (existing or computed) value associated with the specified key, or null if
   *     the computed value is null
   */
  public V computeIfAbsent(
      final K key,
      Function<? super K, ? extends V> mappingFunction,
      final long itemExpirationNano) {

    cleanUp();
    final CacheItem cacheItem = cache.computeIfAbsent(
        key,
        k -> new CacheItem(
            mappingFunction.apply(k),
            System.nanoTime() + itemExpirationNano));
    return cacheItem.withExtendExpiration(itemExpirationNano).item;
  }

  /**
   * Cleanup expired entries if we have hit the cleanup time, then remove and dispose the value
   * associated with the given key.
   *
   * @param key the key associated with the value to be removed/disposed
   */
  public void remove(final K key) {
    removeAndDispose(key);
    cleanUp();
  }

  private void removeAndDispose(K key) {
    final CacheItem cacheItem = cache.remove(key);
    if (cacheItem != null && itemDisposalFunc != null) {
      itemDisposalFunc.dispose(cacheItem.item);
    }
  }

  private void removeIfExpired(K key) {
    final CacheItem cacheItem = cache.get(key);
    if (cacheItem == null || cacheItem.shouldCleanup()) {
      removeAndDispose(key);
    }
  }

  /**
   * Remove and dispose of all entries in the cache.
   */
  public void clear() {
    for (K key : cache.keySet()) {
      removeAndDispose(key);
    }
    cache.clear();
  }

  /**
   * Get a map copy of all entries in the cache, including expired entries.
   *
   * @return a map copy of all entries in the cache, including expired entries
   */
  public Map<K, V> getEntries() {
    final Map<K, V> entries = new HashMap<>();
    for (final Map.Entry<K, CacheItem> entry : this.cache.entrySet()) {
      entries.put(entry.getKey(), entry.getValue().item);
    }
    return entries;
  }

  /**
   * Get the current size of the cache, including expired entries.
   *
   * @return the current size of the cache, including expired entries.
   */
  public int size() {
    return this.cache.size();
  }

  private void cleanUp() {
    if (this.cleanupTimeNanos.get() > System.nanoTime()) {
      return;
    }

    this.cleanupTimeNanos.set(System.nanoTime() + cleanupIntervalNanos);
    cache.forEach((key, value) -> removeIfExpired(key));
  }

  /**
   * Set the cleanup interval for the cache. At cleanup time, expired entries marked for cleanup via
   * {@link ShouldDisposeFunc} (if defined) are disposed.
   *
   * @param cleanupIntervalNanos the time interval defining when we should clean up expired
   *                             entries marked for cleanup, in nanoseconds
   */
  public void setCleanupIntervalNanos(long cleanupIntervalNanos) {
    this.cleanupIntervalNanos = cleanupIntervalNanos;
    this.cleanupTimeNanos.set(System.nanoTime() + cleanupIntervalNanos);
  }

  /**
   * An optional function defining the conditions under which an expired entry should be cleaned up
   * at cleanup time.
   *
   * @param <V> the type of object being analyzed for disposal
   */
  public interface ShouldDisposeFunc<V> {
    boolean shouldDispose(V item);
  }

  /**
   * An optional function defining extra cleanup steps to take when a cache item is cleaned up.
   *
   * @param <V> the type of object being disposed
   */
  public interface ItemDisposalFunc<V> {
    void dispose(V item);
  }

  // For testing purposes only
  Map<K, CacheItem> getCache() {
    return cache;
  }

  class CacheItem {
    private final V item;
    private long expirationTimeNano;

    /**
     * CacheItem constructor.
     *
     * @param item               the item value
     * @param expirationTimeNano the amount of time before a CacheItem should be marked as expired.
     */
    public CacheItem(final V item, final long expirationTimeNano) {
      this.item = item;
      this.expirationTimeNano = expirationTimeNano;
    }

    /**
     * Determines if a cache item should be cleaned up. An item should be cleaned up if it has past
     * its expiration time and {@link ShouldDisposeFunc} (if defined) indicates that it should be
     * cleaned up.
     *
     * @return true if the cache item should be cleaned up at cleanup time. Otherwise, returns
     *     false.
     */
    boolean shouldCleanup() {
      if (shouldDisposeFunc != null) {
        return System.nanoTime() > expirationTimeNano && shouldDisposeFunc.shouldDispose(this.item);
      }
      return System.nanoTime() > expirationTimeNano;
    }

    /**
     * Renew a cache item's expiration time and return the value.
     *
     * @param itemExpirationNano the new expiration duration for the item
     * @return the item value
     */
    public CacheItem withExtendExpiration(final long itemExpirationNano) {
      this.expirationTimeNano = System.nanoTime() + itemExpirationNano;
      return this;
    }

    @Override
    public int hashCode() {
      final int prime = 31;
      int result = 1;
      result = prime * result + ((item == null) ? 0 : item.hashCode());
      return result;
    }

    @Override
    public boolean equals(final Object obj) {
      if (this == obj) {
        return true;
      }
      if (obj == null) {
        return false;
      }
      if (getClass() != obj.getClass()) {
        return false;
      }
      final CacheItem other = (CacheItem) obj;
      if (item == null) {
        return other.item == null;
      } else {
        return item.equals(other.item);
      }
    }

    @Override
    public String toString() {
      return "CacheItem [item=" + item + ", expirationTime=" + expirationTimeNano + "]";
    }
  }
}
