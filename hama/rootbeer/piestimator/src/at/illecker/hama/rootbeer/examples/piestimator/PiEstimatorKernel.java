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
package at.illecker.hama.rootbeer.examples.piestimator;

import edu.syr.pcpratts.rootbeer.runtime.Kernel;

public class PiEstimatorKernel implements Kernel {

  private long m_iterations;
  public double result = 0;

  public PiEstimatorKernel(long iterations) {
    m_iterations = iterations;
  }

  public void gpuMethod() {
    long in = 0;
    for (long i = 0; i < m_iterations; i++) {
      double x = 2.0 * Math.random() - 1.0, y = 2.0 * Math.random() - 1.0;
      if ((Math.sqrt(x * x + y * y) < 1.0)) {
        in++;
      }
    }
    result = 4.0 * in / m_iterations;
  }

  public static void main(String[] args) {
    // Dummy constructor invocation
    // to keep kernel constructor in
    // rootbeer transformation
    new PiEstimatorKernel(0);
  }
}
