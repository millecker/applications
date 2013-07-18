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
#include "util/cuPrintf.cu"
#include <stdio.h>

// includes CUDA Runtime
#include <cuda_runtime.h>

// Convenience function for checking CUDA runtime API results
// can be wrapped around any runtime API call. No-op in release builds.
inline cudaError_t checkCuda(cudaError_t result) {
#if defined(DEBUG) || defined(_DEBUG)
	if (result != cudaSuccess) {
		fprintf(stderr, "CUDA Runtime Error: %s\n", cudaGetErrorString(result));
		assert(result == cudaSuccess);
	}
#endif
	return result;
}

__global__ void device_method(float *d_array, unsigned int n) {

	cuPrintf("Device array:\n");
	for (int i = 0; i < n; ++i) {
		cuPrintf("%.2f ", d_array[i]);
	}
	cuPrintf("\n");

	cuPrintf("Device method increments array elements...\n");

	for (int i = 0; i < n; ++i) {
		d_array[i]++;
	}
}

int main(void) {

	//check if the device supports mapping host memory.
	cudaDeviceProp prop;
	int whichDevice;
	checkCuda(cudaGetDevice(&whichDevice));
	checkCuda(cudaGetDeviceProperties(&prop, whichDevice));
	if (prop.canMapHostMemory != 1) {
		printf("Device cannot map memory \n");
		return 0;
	}

	// host arrays
	float *host_array;
	// device array
	float *device_array;

	unsigned int N = 10;
	const unsigned int size = N * sizeof(float);

	// runtime must be placed into a state enabling to allocate zero-copy buffers.
	checkCuda(cudaSetDeviceFlags(cudaDeviceMapHost));

	// init pinned memory
	checkCuda(
			cudaHostAlloc((void**) &host_array, size,
					cudaHostAllocWriteCombined | cudaHostAllocMapped));

	// init array
	printf("Host array:\n");
	for (int i = 0; i < N; ++i) {
		host_array[i] = i;
		printf("%.2f ", host_array[i]);
	}
	printf("\n");

	checkCuda(cudaHostGetDevicePointer(&device_array, host_array, 0));

	// initialize cuPrintf
	cudaPrintfInit();

	// launch a kernel with a single thread
	device_method<<<1, 1>>>(device_array, N);

	// display the device's output
	cudaPrintfDisplay();
	// clean up after cuPrintf
	cudaPrintfEnd();

	printf("Host array after modification within kernel:\n");
	for (int i = 0; i < N; ++i) {
		printf("%.2f ", host_array[i]);
	}
	printf("\n");

	return 0;
}
