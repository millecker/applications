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
#include <stdio.h>

__constant__ unsigned int shift1[4] = {6, 2, 13, 3};
__constant__ unsigned int shift2[4] = {13, 27, 21, 12};
__constant__ unsigned int shift3[4] = {18, 2, 7, 13};
__constant__ unsigned int offset[4] = {4294967294, 4294967288, 4294967280, 4294967168};
__shared__ unsigned int randStates[32];

__device__ unsigned int TausStep(unsigned int &z, int S1, int S2, int S3, unsigned int M) {
  unsigned int b = (((z << S1) ^ z) >> S2);
  return z = (((z &M) << S3) ^ b);
}

__device__ unsigned int randInt() {
  TausStep(randStates[threadIdx.x&31], shift1[threadIdx.x&3], shift2[threadIdx.x&3],shift3[threadIdx.x&3],offset[threadIdx.x&3]);
  return (randStates[(threadIdx.x)&31]^randStates[(threadIdx.x+1)&31]^randStates[(threadIdx.x+2)&31]^randStates[(threadIdx.x+3)&31]);
}


__global__ void sync_test(void) {
  __shared__ int shared_int;
  int count = 0;
  long long timeout = 0;

  if (threadIdx.x == 0) {
    shared_int = 0;
  }
  __syncthreads();

  if (threadIdx.x == 0) {
    // occupy thread0
    while (count < 100) {
      for (int i=0; i<200; i++){
        randInt();
      }
      if (++timeout > 1000000) {
        break;
      }
      count++;
      if (count > 50) {
        count = 0;
      }
    }
    
    shared_int = 1;
  }
  __syncthreads();

  printf("%d\n", shared_int);
}

int main(void)
{
  sync_test<<<1, 4>>>();
  cudaDeviceSynchronize();
  return 0;
}

/* prints:
1
1
1
1
*/

