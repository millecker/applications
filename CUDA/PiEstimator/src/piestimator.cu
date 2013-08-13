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
#include <sys/time.h>
#include <time.h>
#include <cuda_runtime.h>

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
 * Device function
 */
__global__ void device_method(float *d_x, float *d_y, int *d_inside, int n) {

	unsigned block_id = blockIdx.y * gridDim.x + blockIdx.x;
	int thread_id = threadIdx.x + block_id * blockDim.x;

	if (thread_id < n) {
		float x = d_x[thread_id];
		float y = d_y[thread_id];

		if (sqrt(x * x + y * y) <= 1) {
			d_inside[thread_id] = 1;
		} else {
			d_inside[thread_id] = 0;
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

float rand_float_range(float low, float high) {
	return low + (((float) rand() / RAND_MAX) * (high - low));
}

// not a uniform distribution
void random_number_generator(float* array, int size) {
	for (int i = 0; i < size; i++) {
		array[i] = rand_float_range(-1, 1);
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

	unsigned maxbytes = devProp.totalGlobalMem / 3;
	//because we need 3 arrays (h_x, h_y, h_inside)
	unsigned max_samples = maxbytes / sizeof(float);
	// Does GPU support sample size?
	int n = 2e8;
	if (n > max_samples) {
		n = max_samples;
	}
	printf("Using %d samples to estimate pi\n", n);

	struct timespec total_start;
	struct timespec total_stop;

	clock_gettime(CLOCK_MONOTONIC, &total_start);

	// random scattering of (x,y) points
	float *h_x = NULL;
	float *h_y = NULL;
	int *h_inside = NULL;
	unsigned bytes = sizeof(float) * n;

	// allocate memory
	checkCuda(cudaSetDeviceFlags(cudaDeviceMapHost));
	checkCuda(
			cudaHostAlloc(&h_x, bytes,
					cudaHostAllocWriteCombined | cudaHostAllocMapped));
	checkCuda(
			cudaHostAlloc(&h_y, bytes,
					cudaHostAllocWriteCombined | cudaHostAllocMapped));
	checkCuda(
			cudaHostAlloc(&h_inside, sizeof(int) * n,
					cudaHostAllocWriteCombined | cudaHostAllocMapped));

	// fill with random numbers
	printf("Start filling x and y float arrays with random numbers\n");
	srand((unsigned) time(0));
	random_number_generator(h_x, n);
	random_number_generator(h_y, n);
	printf("End filling x and y float arrays with random numbers\n");

	// init hits array
	for (int i = 0; i < n; i++) {
		h_inside[i] = 0;
	}

	// setup GPU parameters
	dim3 threads(256);
	dim3 blocks(divup(n, threads.x));
	if (blocks.x > 65535) {
		blocks.y = divup(blocks.x, 65535);
		blocks.x = divup(blocks.x, blocks.y);
	}

	float *d_x = NULL;
	float *d_y = NULL;
	int *d_inside = NULL;
	checkCuda(cudaHostGetDevicePointer(&d_x, h_x, 0));
	checkCuda(cudaHostGetDevicePointer(&d_y, h_y, 0));
	checkCuda(cudaHostGetDevicePointer(&d_inside, h_inside, 0));

	printf("Run computation on GPU\n");
	// create cuda event handles
	cudaEvent_t gpu_start, gpu_stop;
	float gpu_time = 0.0f;
	checkCuda(cudaEventCreate(&gpu_start));
	checkCuda(cudaEventCreate(&gpu_stop));

	cudaEventRecord(gpu_start, 0);
	device_method<<<blocks, threads>>>(d_x, d_y, d_inside, n);
	cudaEventRecord(gpu_stop, 0);

	// Check for CUDA runtime errors
	checkCuda(cudaDeviceSynchronize());
	checkCuda(cudaGetLastError());

	printf("Counting hits\n");
	// count how many fell inside
	int hits = 0;
	vector_sum(&hits, h_inside, n);
	printf("hits: %d\n", hits);

	printf("Calculating PI\n");
	// approximate PI
	float pi = 4.0f * hits / n;
	printf("pi: %f\n", pi);

	checkCuda(cudaEventElapsedTime(&gpu_time, gpu_start, gpu_stop));

	clock_gettime(CLOCK_MONOTONIC, &total_stop);
	double total_time = (total_stop.tv_sec - total_start.tv_sec) * 1000000.0f
			+ (total_stop.tv_nsec - total_start.tv_nsec) / 1000.0f;
	printf("gpu time: %.2f ms\n", gpu_time);
	printf("total time: %.2f ms (%ld sec)\n", total_time,
			(total_stop.tv_sec - total_start.tv_sec));

	checkCuda(cudaFreeHost(h_x));
	checkCuda(cudaFreeHost(h_y));
	checkCuda(cudaFreeHost(h_inside));

	return 0;
}
