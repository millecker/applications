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
package at.illecker.hama.hybrid.examples.testglobalgpusync;

import org.trifort.rootbeer.runtime.HamaPeer;
import org.trifort.rootbeer.runtime.Kernel;
import org.trifort.rootbeer.runtime.RootbeerGpu;

public class TestGlobalGpuSyncKernel implements Kernel {

  private String m_peerName = null;
  private String m_masterTask = null; // input
  public int messageCount;
  public int messageSum;

  public TestGlobalGpuSyncKernel(String masterTask) {
    this.m_masterTask = masterTask;
  }

  public void gpuMethod() {
    int threadId = RootbeerGpu.getThreadId();
    // System.out.println(threadId);

    // Each Kernel sends a message including its global threadId
    HamaPeer.send(m_masterTask, threadId);

    // Sync all blocks Inter-Block Synchronization
    RootbeerGpu.syncblocks(1);

    if (threadId == 0) {

      // Sync with other Peers, this call blocks
      HamaPeer.sync();

      m_peerName = HamaPeer.getPeerName();

      if (m_peerName.equals(m_masterTask)) {
        messageSum = 0;
        messageCount = HamaPeer.getNumCurrentMessages();
        System.out.print("Global Thread0 fetch messages: ");
        System.out.println(messageCount);

        for (int i = 0; i < messageCount; i++) {
          int message = HamaPeer.getCurrentIntMessage();
          // System.out.println(message);
          messageSum += message;
        }
        System.out.print("MessageSum: ");
        System.out.println(messageSum);
      }
    }

  }

  public static void main(String[] args) {
    // Dummy constructor invocation
    // to keep kernel constructor in
    // rootbeer transformation
    new TestGlobalGpuSyncKernel("");
  }
}
