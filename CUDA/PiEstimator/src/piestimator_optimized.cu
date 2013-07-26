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
#include <time.h>

#include <cuda_runtime.h>
#include <curand_kernel.h>

// Convenience function for checking CUDA runtime API results
// can be wrapped around any runtime API call. No-op in release builds.
inline cudaError_t checkCuda(cudaError_t result) {
	if (result != cudaSuccess) {
		fprintf(stderr, "CUDA Runtime Error: %s\n", cudaGetErrorString(result));
		assert(result == cudaSuccess);
	}
	return result;
}

/**
 * Device functions
 */

__device__ inline void getPoint(float &x, float &y, curandState &state) {
	x = curand_uniform(&state);
	y = curand_uniform(&state);
}

__device__ inline void getPoint(double &x, double &y, curandState &state) {
	x = curand_uniform_double(&state);
	y = curand_uniform_double(&state);
}

// RNG init kernel
__global__ void initRNG(curandState * const rngStates, int n,
		const unsigned int seed) {
	// Determine thread ID
	unsigned int thread_id = blockIdx.x * blockDim.x + threadIdx.x;

	if (thread_id < n) {
		// Initialise the RNG
		curand_init(seed, thread_id, 0, &rngStates[thread_id]);
	}
}

// Compute on Iteration
__global__ void device_method(int *d_hits, int n,
		curandState * const rngStates) {

	unsigned block_id = blockIdx.y * gridDim.x + blockIdx.x;
	int thread_id = threadIdx.x + block_id * blockDim.x;

	if (thread_id < n) {

		// Initialise the RNG
		curandState localState = rngStates[thread_id];
		float x;
		float y;
		getPoint(x, y, localState);

		x = -1 + ((x / RAND_MAX) * 2);
		y = -1 + ((y / RAND_MAX) * 2);

		if (sqrt(x * x + y * y) <= 1) {
			d_hits[thread_id] = 1;
		} else {
			d_hits[thread_id] = 0;
		}
	}
}

int divup(int x, int y) {
	if (x % y != 0) {
		// aufrunden
		return ((x + y - 1) / y);
	} else {
		return x / y;
	}
}

void vector_sum(int* result, int* array, int size) {
	for (int i = 0; i < size; i++) {
		//printf("h_inside[%d]=%d\n", i, array[i]);
		*result += array[i];
	}
}

/**
 * Host function
 */
int main(void) {

	// Fetch available GPU memory size to fix the samples count.
	cudaDeviceProp devProp;
	checkCuda(cudaGetDeviceProperties(&devProp, 0));

	unsigned maxbytes = devProp.totalGlobalMem;
	//because we need 3 arrays (h_x, h_y, h_inside)
	unsigned max_samples = maxbytes / (sizeof(int) + sizeof(curandState));
	// Does GPU support sample size?
	int n = 2e6;
	if (n > max_samples) {
		n = max_samples;
	}
	printf("Using %d samples to estimate pi\n", n);

	// setup GPU parameters
	dim3 threads(256);
	dim3 blocks(divup(n, threads.x));
	if (blocks.x > 65535) {
		blocks.y = divup(blocks.x, 65535);
		blocks.x = divup(blocks.x, blocks.y);
	}

	printf("Threads.x: %d, Blocks.x: %d, Blocks.y: %d\n", threads.x, blocks.x,
			blocks.y);

	// set to pinned memory
	checkCuda(cudaSetDeviceFlags(cudaDeviceMapHost));

	// Allocate memory for RNG states
	curandState *h_rngStates = 0;
	curandState *d_rngStates = 0;
	checkCuda(
			cudaHostAlloc(&h_rngStates, sizeof(curandState) * n,
					cudaHostAllocWriteCombined | cudaHostAllocMapped));
	checkCuda(cudaHostGetDevicePointer(&d_rngStates, h_rngStates, 0));

	// Allocate hits array
	int *h_hits = NULL;
	int *d_hits = NULL;
	checkCuda(
			cudaHostAlloc(&h_hits, sizeof(int) * n,
					cudaHostAllocWriteCombined | cudaHostAllocMapped));
	// Init hits array
	for (int i = 0; i < n; i++) {
		h_hits[i] = 0;
	}

	checkCuda(cudaHostGetDevicePointer(&d_hits, h_hits, 0));

	printf("Init RNG\n");
	unsigned int seed = (unsigned) time(0);
	initRNG<<<blocks, threads>>>(d_rngStates, n, seed);

	printf("Run computation on GPU\n");
	device_method<<<blocks, threads>>>(d_hits, n, d_rngStates);

#ifdef DEBUG // For debugging purposes
// Check for CUDA runtime errors
	CUDA(cudaDeviceSynchronize());
	CUDA(cudaGetLastError());
#endif

	// count how many fell inside
	int hits = 0;
	vector_sum(&hits, h_hits, n);
	printf("hits: %d\n", hits);

	// approximate PI
	float pi = 4.0f * hits / n;
	printf("pi: %f\n", pi);

	checkCuda(cudaFreeHost(h_hits));

	return 0;
}
