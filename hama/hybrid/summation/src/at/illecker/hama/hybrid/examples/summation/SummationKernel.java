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
package at.illecker.hama.hybrid.examples.summation;

import edu.syr.pcpratts.rootbeer.runtime.HamaPeer;
import edu.syr.pcpratts.rootbeer.runtime.Kernel;

public class SummationKernel implements Kernel {

  private String m_masterTask;
  public int numPeers = 0;
  public String peerName;

  public SummationKernel(String masterTask) {
    this.m_masterTask = masterTask;
  }

  public void gpuMethod() {
    numPeers = HamaPeer.getNumPeers();

    double intermediateSum = 0.0;
    int key = 1; // [-128, 0] java_lang_Integer_valueOf11_5_ will fail
    int value = 1; // [-128, 0] java_lang_Integer_valueOf11_5_ will fail

    // autoboxing to Integer
    //HamaPeer.readNext(key, value);
    System.out.print("SummationBSP.bsp key: ");
    System.out.println(key);
    System.out.print("SummationBSP.bsp value: ");
    System.out.println(value);
      // intermediateSum += Double.parseDouble(value);
    
    HamaPeer.readNext(new Integer(0), new Integer(0));
    HamaPeer.readNext(new Long(0), new Long(0)); 
    HamaPeer.readNext(new Float(0), new Float(0)); 
    HamaPeer.readNext(new Double(0), new Double(0)); 
    HamaPeer.readNext(new String(), new String());     

    System.out.print("SummationBSP.bsp send intermediateSum: ");
    System.out.println(intermediateSum);

    HamaPeer.sendDouble(m_masterTask, intermediateSum);
    HamaPeer.sync();

    // Consume messages
    peerName = HamaPeer.getPeerName();
    if (peerName.equals(m_masterTask)) {

      double sum = 0.0;
      int msg_count = HamaPeer.getNumCurrentMessages();
      System.out.print("SummationBSP.bsp msg_count: ");
      System.out.println(msg_count);

      for (int i = 0; i < msg_count; i++) {
        double msg = HamaPeer.getCurrentDoubleMessage();
        System.out.print("SummationBSP.bsp message: ");
        System.out.println(msg);
        sum += msg;
      }

      System.out.print("SummationBSP.bsp write Sum: ");
      System.out.println(sum);
      // HamaPeer.write("Sum", sum);
    }
  }

  public static void main(String[] args) {
    // Dummy constructor invocation
    // to keep kernel constructor in
    // rootbeer transformation
    new SummationKernel(new String());
  }
}
