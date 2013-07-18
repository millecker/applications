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

// includes, system
#include <stdio.h>
#include <assert.h>

// includes CUDA Runtime
#include <cuda_runtime.h>

#define N 4096

/**
 * Convenience function for checking CUDA runtime API results
 * can be wrapped around any runtime API call. No-op in release builds.
 */
inline cudaError_t checkCuda(cudaError_t result) {
#if defined(DEBUG) || defined(_DEBUG)
	if (result != cudaSuccess) {
		fprintf(stderr, "CUDA Runtime Error: %s\n", cudaGetErrorString(result));
		assert(result == cudaSuccess);
	}
#endif
	return result;
}

/**
 * CUDA kernel function
 */
__global__ void increment_kernel(unsigned long long *cnt) {
	atomicAdd(cnt, 1);
}

/**
 * Host function
 */
int main(int argc, char **argv) {

	// Check CUDA device for pinned memory feature
	int myDevice;
	cudaDeviceProp deviceProps;
	cudaGetDevice(&myDevice);
	cudaGetDeviceProperties(&deviceProps, myDevice);
	if (deviceProps.canMapHostMemory != 1) {
		printf("MapHostMemory feature not available!\n");
		return 0;
	} else {
		printf("CUDA device [%s] supports canMapHostMemory!\n",
				deviceProps.name);
	}

	unsigned long long *cnt; // host pointers
	unsigned long long *dev_cnt; // device pointers to host memory

	// runtime must be placed into a state enabling to allocate zero-copy buffers.
	checkCuda(cudaSetDeviceFlags(cudaDeviceMapHost));

	// allocate pinned/mapped memory can be accessed directly from within kernel
	checkCuda(
			cudaHostAlloc((void**) &cnt, sizeof(unsigned long long),
					cudaHostAllocMapped));

	// init counter to 0
	*cnt = 0;
	printf("Init Host Counter: %lu\n", *cnt);

	checkCuda(cudaHostGetDevicePointer(&dev_cnt, cnt, 0));

	for (int i = 0; i < N; i++) {
		increment_kernel<<<4, 4>>>(dev_cnt);
	}

	cudaThreadSynchronize();

	assert(*cnt == 16*N);
	printf("Host Counter: %lu (after gpu execution)\n", *cnt);

	return 0;
}
