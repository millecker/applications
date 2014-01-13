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

public class DenseDoubleVector {
  private double m_vector[];
  private int m_index;

  public DenseDoubleVector(int len) {
    m_vector = new double[len];
    m_index = 0;
  }

  public int getLength() {
    return m_index;
  }

  public void set(int index, double value) {
    m_vector[m_index] = value;
    m_index++;
  }

  public double get(int index) {
    return m_vector[index];
  }

  public double dot(DenseDoubleVector otherVector) {
    double dotProduct = 0.0;
    for (int i = 0; i < m_index; i++) {
      dotProduct += m_vector[i] * otherVector.get(i);
    }
    return dotProduct;
  }

  @Override
  public String toString() {
    String str = "";
    String delimiter = ",";
    for (int i = 0; i < m_index; i++) {
      String val = Double.toString(m_vector[i]);
      // System.out.println(val);
      if (i == 0) {
        str += "" + val;
      } else {
        str += delimiter + " " + val;
      }
    }
    return str;
  }
}
