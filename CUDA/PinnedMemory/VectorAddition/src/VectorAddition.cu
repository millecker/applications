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
#include <stdlib.h>
#include <assert.h>

// includes CUDA Runtime
#include <cuda_runtime.h>

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

__global__ void addVector(int *a, int *b, int *c, unsigned int n) {
	int thread_idxx = blockIdx.x * blockDim.x + threadIdx.x;
	if (thread_idxx < n) {
		c[thread_idxx] = a[thread_idxx] + b[thread_idxx];
	}
}

void printVector(int *v, unsigned int n) {
	for (int i = 0; i < n; i++) {
		if (i + 1 < n) {
			printf("%d, ", v[i]);
		} else {
			printf("%d", v[i]);
		}
	}
	printf("\n");
}

int main(int argc, char *argv[]) {
	unsigned int N = 32; // size of vectors
	const unsigned int size = N * sizeof(int);

	int T = 32, B = 1; // threads per block and blocks per grid

	int *a, *b, *c; // host pointers
	int *dev_a, *dev_b, *dev_c; // device pointers to host memory


	// runtime must be placed into a state enabling to allocate zero-copy buffers.
	checkCuda(cudaSetDeviceFlags(cudaDeviceMapHost));

	checkCuda(
			cudaHostAlloc((void**) &a, size,
					cudaHostAllocWriteCombined | cudaHostAllocMapped));
	checkCuda(
			cudaHostAlloc((void**) &b, size,
					cudaHostAllocWriteCombined | cudaHostAllocMapped));

	checkCuda(cudaHostAlloc((void**) &c, size, cudaHostAllocMapped));

	// init vectors
	for (int i = 0; i < N; i++) {
		a[i] = i;
		b[i] = i;
	}
	printf("Vector A: \n");
	printVector(a, N);
	printf("Vector B: \n");
	printVector(b, N);

	// mem. copy to device not need now, but ptrs needed instead
	checkCuda(cudaHostGetDevicePointer(&dev_a, a, 0));
	checkCuda(cudaHostGetDevicePointer(&dev_b, b, 0));
	checkCuda(cudaHostGetDevicePointer(&dev_c, c, 0));

	// to measure time
	cudaEvent_t start, stop;
	float elapsed_time_ms;
	cudaEventCreate(&start);
	cudaEventCreate(&stop);
	cudaEventRecord(start, 0);

	addVector<<<B, T>>>(dev_a, dev_b, dev_c, N);

	// copy back not needed but now need thread synchronization
	cudaThreadSynchronize();
	cudaEventRecord(stop, 0);

	// 	print results
	printf("Vector C: \n");
	printVector(c, N);

	cudaEventElapsedTime(&elapsed_time_ms, start, stop);
	// print out execution time
	printf("Time to calculate results: %.2f ms.\n", elapsed_time_ms);

	// clean up
	cudaFreeHost(a);
	cudaFreeHost(b);
	cudaFreeHost(c);

	cudaEventDestroy(start);
	cudaEventDestroy(stop);

	return 0;
}
