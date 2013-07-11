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
package at.illecker.hama.rootbeer.examples.matrixmultiplication.gpu;

import java.util.ArrayList;
import java.util.List;

public class ResultList {

  private Result[] m_data;
  private int m_size;

  public ResultList() {
    m_data = new Result[8];
    m_size = 0;
  }

  public void add(Result newResult) {
    m_data[m_size] = newResult;
    ++m_size;

    if (m_size == m_data.length) {
      Result[] new_data = new Result[m_size * 2];
      for (int i = 0; i < m_size - 1; ++i) {
        new_data[i] = m_data[i];
      }
      m_data = new_data;
    }
  }

  public List<Result> getList() {
    List<Result> ret = new ArrayList<Result>();
    for (int i = 0; i < m_size - 1; ++i) {
      ret.add(m_data[i]);
    }
    return ret;
  }
}
