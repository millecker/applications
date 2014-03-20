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

public final class GpuIntegerListMap {
  public static final int DEFAULT_CAPACITY = 16;
  private GpuIntIntPair[] m_values = null;
  private boolean m_used = false;

  public GpuIntegerListMap() {
    this(DEFAULT_CAPACITY);
  }

  public GpuIntegerListMap(int size) {
    this.m_values = new GpuIntIntPair[size];
  }

  public void clear() {
    if (m_used) {
      for (int i = 0; i < m_values.length; i++) {
        m_values[i] = null;
      }
    }
  }

  private boolean equalsKey(GpuIntIntPair entry, int otherKey) {
    if (entry != null) {
      return (entry.getKey() == otherKey);
    }
    return false;
  }

  public int indexForKey(int key) {
    return (key % m_values.length);
  }

  public Integer get(int key) {
    GpuIntIntPair entry = m_values[indexForKey(key)];
    while (entry != null && !equalsKey(entry, key)) {
      entry = entry.getNext();
    }
    return (entry != null) ? entry.getValue() : null;
  }

  public GpuIntIntPair getList(int key) {
    return m_values[indexForKey(key)];
  }

  public void put(int key, int value) {
    m_used = true;
    int bucketIndex = indexForKey(key);
    GpuIntIntPair entry = m_values[bucketIndex];
    if (entry != null) {
      boolean done = false;
      while (!done) {
        if (entry.getNext() == null) {
          entry.setNext(new GpuIntIntPair(key, value));
          done = true;
        }
        entry = entry.getNext();
      }
    } else {
      m_values[bucketIndex] = new GpuIntIntPair(key, value);
    }
  }

}
