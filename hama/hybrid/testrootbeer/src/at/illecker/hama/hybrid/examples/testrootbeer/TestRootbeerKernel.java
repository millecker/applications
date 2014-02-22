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
package at.illecker.hama.hybrid.examples.testrootbeer;

import org.trifort.rootbeer.runtime.HamaPeer;
import org.trifort.rootbeer.runtime.Kernel;
import org.trifort.rootbeer.runtime.KeyValuePair;
import org.trifort.rootbeer.runtime.RootbeerGpu;

public class TestRootbeerKernel implements Kernel {

  public int[] input;
  public String peerName;
  public String[] allPeerNames;

  public TestRootbeerKernel(int n) {
    this.input = new int[n];
  }

  public void gpuMethod() {
    System.out.println(RootbeerGpu.getThreadId());

    // read input within one kernel of each block only -> GPUTime=926 ms
    if (RootbeerGpu.getThreadIdxx() == 0) {
      System.out.print("BlockSize: ");
      System.out.println(RootbeerGpu.getBlockDimx());
      System.out.print("GridSize: ");
      System.out.println(RootbeerGpu.getGridDimx());
      peerName = HamaPeer.getPeerName();
      allPeerNames = HamaPeer.getAllPeerNames();

      // System.out.println("input values:");
      int key = 1;
      int value = 1;
      KeyValuePair keyValuePair = new KeyValuePair(key, value);
      while (HamaPeer.readNext(keyValuePair)) {
        key = (Integer) keyValuePair.getKey();
        value = (Integer) keyValuePair.getValue();
        input[key] = value; //
        // System.out.println(value);
      }

    }

    RootbeerGpu.syncthreads();

    // test input within every kernel -> GPUTime=18165 ms sync overhead!!!
    // int key = 1;
    // int value = 1;
    // KeyValuePair keyValuePair = new KeyValuePair(key, value);
    // if (HamaPeer.readNext(keyValuePair)) {
    // key = (Integer) keyValuePair.getKey();
    // value = (Integer) keyValuePair.getValue();
    // input[key] = value; //
    // System.out.println(value);
    // }
  }

  public static void main(String[] args) {
    // Dummy constructor invocation
    // to keep kernel constructor in
    // rootbeer transformation
    new TestRootbeerKernel(0);
  }
}
