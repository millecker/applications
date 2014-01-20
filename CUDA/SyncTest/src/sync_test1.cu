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

__global__ void sync_test(void)
{
  printf("a\n");

  if(threadIdx.x == 0){
    __syncthreads();
    __syncthreads();
  } else {
    __syncthreads();
  }

  printf("b\n");

  if(threadIdx.x != 0){
    printf("c\n");
    __syncthreads();
  }
}

int main(void)
{
  sync_test<<<1, 4>>>();
  cudaDeviceSynchronize();
  return 0;
}

/* prints:
a
a
a
a
b
b
b
b
c
c
c
*/

/* desired: 
a
a
a
a
b
b
b
c
c
c
b
*/
