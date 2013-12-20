/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package at.illecker.hama.hybrid.examples.kmeans;

import java.util.ArrayList;
import java.util.List;

public class DenseDoubleVectorList {
  private DenseDoubleVector m_values[];
  private int m_index;

  public DenseDoubleVectorList() {
    m_values = new DenseDoubleVector[8];
    m_index = 0;
  }

  public DenseDoubleVectorList(int length) {
    m_values = new DenseDoubleVector[length];
    m_index = 0;
  }

  public int getLength() {
    return m_index;
  }

  public void add(DenseDoubleVector v) {
    m_values[m_index] = v;
    m_index++;

    if (m_index == m_values.length) {
      DenseDoubleVector[] new_data = new DenseDoubleVector[m_index * 2];
      for (int i = 0; i < m_index; i++) {
        new_data[i] = m_values[i];
      }
      m_values = new_data;
    }
  }

  public DenseDoubleVector get(int index) {
    return m_values[index];
  }

  public List<DenseDoubleVector> getList() {
    List<DenseDoubleVector> ret = new ArrayList<DenseDoubleVector>();
    for (int i = 0; i < m_index; ++i) {
      ret.add(m_values[i]);
    }
    return ret;
  }

}
