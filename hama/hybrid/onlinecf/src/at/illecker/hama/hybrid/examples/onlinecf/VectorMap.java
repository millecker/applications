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

public final class VectorMap {
  public static final int DEFAULT_CAPACITY = 16;
  private KeyValuePair[] m_values = null;

  public VectorMap() {
    this(DEFAULT_CAPACITY);
  }

  public VectorMap(int size) {
    this.m_values = new KeyValuePair[size];
  }

  private boolean equalsKey(KeyValuePair entry, long id) {
    if (entry != null) {
      Long key = (Long) entry.getKey();
      if (key != null) {
        return key.equals(id);
      }
    }
    return false;
  }

  public int indexForKey(long id) {
    return (int) id % m_values.length;
  }

  public double[] get(long id) {
    KeyValuePair entry = m_values[indexForKey(id)];
    while (entry != null && !equalsKey(entry, id)) {
      entry = entry.getNext();
    }
    return (entry != null) ? (double[]) entry.getValue() : null;
  }

  public void put(long id, double[] value) {
    int bucketIndex = indexForKey(id);
    KeyValuePair entry = m_values[bucketIndex];

    if (entry != null) {
      boolean done = false;
      while (!done) {
        if (equalsKey(entry, id)) {
          entry.setValue(value);
          done = true;
        } else if (entry.getNext() == null) {
          entry.setNext(new KeyValuePair(id, value));
          done = true;
        }
        entry = entry.getNext();
      }
    } else {
      m_values[bucketIndex] = new KeyValuePair(id, value);
    }
  }

}
