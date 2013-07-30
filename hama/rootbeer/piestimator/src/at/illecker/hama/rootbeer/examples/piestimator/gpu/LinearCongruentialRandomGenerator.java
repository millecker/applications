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
package at.illecker.hama.rootbeer.examples.piestimator.gpu;

/*
 * LinearCongruentialRandomGenerator (LCG) from [1]
 *  
 * X_k+1 = (a * X_k + c) % m
 *  
 * a is the "multiplier" (0 < a < m)
 * c is the "increment" (0 < c < m)
 * c = 0 in this implementation!
 * X_0 is the seed
 * 
 * m is a prime number (or power of a prime number) 
 *  
 * [1] - Press, W. H., et al. Numerical Recipes in C, 2nd Ed Cambridge University Press, 1992
 * http://www.nr.com
 */
public class LinearCongruentialRandomGenerator {

  private long seed; // Current random seed value

  // maxMultiplier is a pre-computed scaling factor
  // necessary for converting a random long into a uniform value on on
  // the open interval (0,1)
  private double maxMultiplier;

  // Define the constants for the Park & Miller algorithm [2]
  // [2] http://en.wikipedia.org/wiki/Lehmer_RNG
  private static final long a = 16807; // 7^5
  private static long m = 2147483647; // 2^31 - 1

  // Schrage's algorithm constants [3]
  // [3] http://dl.acm.org/citation.cfm?id=355828
  private static long q = 127773;
  private static long r = 2836;

  public LinearCongruentialRandomGenerator(final long seed) {
    this.seed = seed;

    if (this.seed == 0) {
      this.seed = 1;
    }

    this.maxMultiplier = 1.0 / (1.0 + (m - 1));

    // skip first value
    nextDouble();
  }

  public void setSeed(long newSeed) {
    this.seed = newSeed;
  }

  public long getSeed() {
    return this.seed;
  }

  private long nextLong() {
    long k = 0;

    k = this.seed / q;
    this.seed = a * (this.seed - k * q) - r * k;

    if (this.seed < 0) {
      this.seed += m;
    }

    return this.seed;
  }

  public double nextDouble() {
    return nextLong() * this.maxMultiplier;
  }

  public static void main(String[] args) {
    LinearCongruentialRandomGenerator lcg = new LinearCongruentialRandomGenerator(
        System.currentTimeMillis());

    for (int i = 0; i < 10; i++) {
      System.out.println("" + lcg.nextDouble());
    }
  }
}
