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
 * LinearCongruentialRandomGenerator from [1]
 *  
 * [1] - Press, W. H., et al. Numerical Recipes in C, 2nd Ed Cambridge University Press, 1992
 * http://www.nr.com
 */
public class LinearCongruentialRandomGenerator {

  private long initSeed; // Initial random seed value
  private long currentSeed; // Current random seed value

  // max_mutliplier is a pre-computed scaling factor
  // necessary for converting a random long into a uniform value on on
  // the open interval (0,1)
  private double maxMultiplier;

  // Define the constants for the Park & Miller algorithm
  private final long a = 16807; // 7^5
  private final long m = 2147483647; // 2^32 - 1

  // Schrage's algorithm constants
  private final long q = 127773;
  private final long r = 2836;

  public LinearCongruentialRandomGenerator(final long seed) {
    this.initSeed = seed;
    this.currentSeed = seed;

    if (this.initSeed == 0) {
      this.initSeed = 1;
      this.currentSeed = 1;
    }

    this.maxMultiplier = 1.0 / (1.0 + (m - 1));

    // skip first value
    nextDouble();
  }

  public void setSeed(long newSeed) {
    this.currentSeed = newSeed;
  }

  public long getSeed(long newSeed) {
    return this.currentSeed;
  }

  public void resetSeed(long newSeed) {
    this.currentSeed = initSeed;
  }

  /*
   * Linear congruential generators (LCG) 
   * X_k+1 = a * X_k % n
   * 
   * n is a prime number (or power of a prime number) 
   * g has high multiplicative order modulo n 
   * x0 (the initial seed) is co-prime to n
   */
  private long nextLong() {
    long k = 0;

    k = this.currentSeed / q;
    this.currentSeed = a * (this.currentSeed - k * q) - r * k;

    if (this.currentSeed < 0) {
      this.currentSeed += m;
    }

    return this.currentSeed;
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
