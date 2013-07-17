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
#include <assert.h>
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
int main(void) {
	unsigned long long *cnt;

	checkCuda(cudaMallocHost((void**) &cnt, sizeof(unsigned long long)));

	*cnt = 0;

	for (int i = 0; i < N; i++) {
		increment_kernel<<<4, 4>>>(cnt);
	}

	cudaThreadSynchronize();

	fprintf(stderr, "CNT %lu\n", *cnt);

	assert(*cnt == 16*N);
	fprintf(stderr, "CNT %lu\n", *cnt);

	return 0;
}
