/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package at.illecker.hama.hybrid.examples.onlinecf;

import org.trifort.rootbeer.runtime.KeyValuePair;

public final class GpuIntegerListMap {
  public static final int DEFAULT_CAPACITY = 16;
  private KeyValuePair[] m_values = null;
  private boolean m_used = false;

  public GpuIntegerListMap() {
    this(DEFAULT_CAPACITY);
  }

  public GpuIntegerListMap(int size) {
    this.m_values = new KeyValuePair[size];
  }

  public void clear() {
    if (m_used) {
      for (int i = 0; i < m_values.length; i++) {
        m_values[i] = null;
      }
    }
  }

  private boolean equalsKey(KeyValuePair entry, long otherKey) {
    if (entry != null) {
      long key = (Long) entry.getKey();
      return (key == otherKey);
    }
    return false;
  }

  public int indexForKey(long key) {
    return (int) (key % m_values.length);
  }

  public Integer get(long key) {
    KeyValuePair entry = m_values[indexForKey(key)];
    while (entry != null && !equalsKey(entry, key)) {
      entry = entry.getNext();
    }
    return (entry != null) ? (Integer) entry.getValue() : null;
  }

  public KeyValuePair getList(long key) {
    return m_values[indexForKey(key)];
  }

  public void put(long key, int value) {
    m_used = true;
    int bucketIndex = indexForKey(key);
    KeyValuePair entry = m_values[bucketIndex];
    if (entry != null) {
      boolean done = false;
      while (!done) {
        if (entry.getNext() == null) {
          entry.setNext(new KeyValuePair(key, value));
          done = true;
        }
        entry = entry.getNext();
      }
    } else {
      m_values[bucketIndex] = new KeyValuePair(key, value);
    }
  }

}
