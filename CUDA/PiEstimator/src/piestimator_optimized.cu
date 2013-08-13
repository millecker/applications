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

// RNG init kernel
__global__ void initRNG(curandState* rngStates, int n,
		const unsigned int seed) {

	unsigned block_id = blockIdx.y * gridDim.x + blockIdx.x;
	int thread_id = threadIdx.x + block_id * blockDim.x;

	if (thread_id < n) {
		// Initialise the RNG
		curand_init(seed, thread_id, 0, &rngStates[thread_id]);
	}
}

// Compute kernel
__global__ void device_method(int *d_hits, int n,
		curandState * const rngStates) {

	unsigned block_id = blockIdx.y * gridDim.x + blockIdx.x;
	int thread_id = threadIdx.x + block_id * blockDim.x;

	if (thread_id < n) {

		// Initialise the RNG
		curandState localState = rngStates[thread_id];
		float x = curand_uniform(&localState);
		float y = curand_uniform(&localState);

		// A + rnd_number * (B-A);
		x = -1 + (x * 2); // from -1 to 1
		y = -1 + (y * 2); // from -1 to 1

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
	//max samples depending on hints and curandStates array
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

	struct timespec total_start;
	struct timespec total_stop;

	clock_gettime(CLOCK_MONOTONIC, &total_start);

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

	// create cuda event handles
	cudaEvent_t rng_start, rng_stop;
	cudaEvent_t gpu_start, gpu_stop;
	float gpu_rng_time = 0.0f;
	float gpu_time = 0.0f;
	checkCuda(cudaEventCreate(&rng_start));
	checkCuda(cudaEventCreate(&rng_stop));
	checkCuda(cudaEventCreate(&gpu_start));
	checkCuda(cudaEventCreate(&gpu_stop));

	printf("Run RNG gerneration on GPU\n");
	unsigned int seed = (unsigned) time(0);
	cudaEventRecord(rng_start, 0);
	initRNG<<<blocks, threads>>>(d_rngStates, n, seed);
	cudaEventRecord(rng_stop, 0);

	checkCuda(cudaDeviceSynchronize());
	checkCuda(cudaGetLastError());

	printf("Run computation on GPU\n");
	cudaEventRecord(gpu_start, 0);
	device_method<<<blocks, threads>>>(d_hits, n, d_rngStates);
	cudaEventRecord(gpu_stop, 0);

	checkCuda(cudaDeviceSynchronize());
	checkCuda(cudaGetLastError());

	printf("Counting hits\n");
	// count how many fell inside
	int hits = 0;
	vector_sum(&hits, h_hits, n);
	printf("hits: %d\n", hits);

	printf("Calculating PI\n");
	// approximate PI
	float pi = 4.0f * hits / n;
	printf("pi: %f\n", pi);

	checkCuda(cudaEventElapsedTime(&gpu_rng_time, rng_start, rng_stop));
	checkCuda(cudaEventElapsedTime(&gpu_time, gpu_start, gpu_stop));

	clock_gettime(CLOCK_MONOTONIC, &total_stop);
	double total_time = (total_stop.tv_sec - total_start.tv_sec) * 1000000.0f
			+ (total_stop.tv_nsec - total_start.tv_nsec) / 1000.0f;
	printf("gpu rng time: %.2f ms\n", gpu_rng_time);
	printf("gpu computation time: %.2f ms\n", gpu_time);
	printf("total time: %.2f ms (%ld sec)\n", total_time,
			(total_stop.tv_sec - total_start.tv_sec));

	checkCuda(cudaFreeHost(h_hits));

	return 0;
}
