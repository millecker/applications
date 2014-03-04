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
package at.illecker.hama.hybrid.examples.onlinecf;

import org.trifort.rootbeer.runtime.Kernel;

public class OnlineCFTrainHybridKernel implements Kernel {

  private UserItemMap m_userItemMap;
  private VectorMap m_usersMatrix;
  private VectorMap m_itemsMatrix;
  private int m_n;
  private int m_m;
  private int m_matrixRank;
  private int m_maxIterations;
  private String[] m_allPeerNames;

  public OnlineCFTrainHybridKernel(UserItemMap userItemMap,
      VectorMap usersMatrix, VectorMap itemsMatrix, int n, int m,
      int matrixRank, int maxIterations, String[] allPeerNames) {
    this.m_userItemMap = userItemMap;
    this.m_usersMatrix = usersMatrix;
    this.m_itemsMatrix = itemsMatrix;
    this.m_n = n;
    this.m_m = m;
    this.m_matrixRank = matrixRank;
    this.m_maxIterations = maxIterations;
    this.m_allPeerNames = allPeerNames;
  }

  public void gpuMethod() {
    System.out.println("userItemMap (1,1," + m_userItemMap.get(1, 1) + ")");
    System.out.println("userItemMap (1,2," + m_userItemMap.get(1, 2) + ")");
    System.out.println("userItemMap (1,3," + m_userItemMap.get(1, 3) + ")");
    System.out.println("userItemMap (1,4," + m_userItemMap.get(1, 4) + ")");

    System.out.println("usersMatrix (1," + arrayToString(m_usersMatrix.get(1))
        + ")");
    System.out.println("usersMatrix (2," + arrayToString(m_usersMatrix.get(2))
        + ")");
    System.out.println("usersMatrix (3," + arrayToString(m_usersMatrix.get(3))
        + ")");

    System.out.println("itemsMatrix (1," + arrayToString(m_itemsMatrix.get(1))
        + ")");
    System.out.println("itemsMatrix (2," + arrayToString(m_itemsMatrix.get(2))
        + ")");
    System.out.println("itemsMatrix (3," + arrayToString(m_itemsMatrix.get(3))
        + ")");
    System.out.println("itemsMatrix (4," + arrayToString(m_itemsMatrix.get(4))
        + ")");
    System.out.println("itemsMatrix (5," + arrayToString(m_itemsMatrix.get(5))
        + ")");
  }

  private String arrayToString(double[] arr) {
    if (arr != null) {
      String result = "[";
      for (int i = 0; i < arr.length; i++) {
        result += (i + 1 == arr.length) ? arr[i] : (arr[i] + ",");
      }
      result += "]";
      return result;
    }
    return "null";
  }

  public static void main(String[] args) {
    // Dummy constructor invocation
    // to keep kernel constructor in
    // rootbeer transformation
    new OnlineCFTrainHybridKernel(null, null, null, 0, 0, 0, 0, null);
    new UserItemMap().put(0, 0, 0);
    new VectorMap().put(0, null);
  }
}
