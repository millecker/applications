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

import edu.syr.pcpratts.rootbeer.runtime.HamaPeer;
import edu.syr.pcpratts.rootbeer.runtime.Kernel;
import edu.syr.pcpratts.rootbeer.runtime.KeyValuePair;
import edu.syr.pcpratts.rootbeer.runtime.RootbeerGpu;

public class TestRootbeerKernel implements Kernel {

  private String examplePath;
  private String[] tmp;

  public int[] input;
  public String peerName;

  public TestRootbeerKernel(String examplePath, int n) {
    this.examplePath = examplePath;
    this.input = new int[n];
    // this.summation = new int[n];
  }

  public void gpuMethod() {
    System.out.println(RootbeerGpu.getThreadId());

    if (RootbeerGpu.getThreadIdxx() == 0) {
      // is required for
      // error: identifier "java_lang_String__array_new" is undefined
      tmp = new String[] { "test" };

      System.out.print("BlockSize: ");
      System.out.println(RootbeerGpu.getBlockDimx());
      System.out.print("GridSize: ");
      System.out.println(RootbeerGpu.getGridDimx());
      peerName = HamaPeer.getPeerName();
      // System.out.println("input values:");

      // test input within one kernel only -> GPUTime=926 ms
      int key = 1;
      int value = 1;
      KeyValuePair keyValuePair = new KeyValuePair(key, value);
      while (HamaPeer.readNext(keyValuePair)) {
        key = (Integer) keyValuePair.getKey();
        value = (Integer) keyValuePair.getValue();
        input[key] = value; //
        System.out.println(value);
      }

    }

    RootbeerGpu.syncthreads();

    // test input within each kernel -> GPUTime=18165 ms sync overhead!!!
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
    new TestRootbeerKernel(new String(""), 0);
  }
}
