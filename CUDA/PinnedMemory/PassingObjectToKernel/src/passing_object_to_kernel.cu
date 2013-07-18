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

#include "MyClass.h"
#include "util/cuPrintf.cu"

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

__global__ void device_method(MyClass *d_object) {

	//int val = d_object->getValue();
	//cuPrintf("Device object value: %d\n", val);
	//d_object->setValue(++val);
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

	MyClass *host_object;
	MyClass *device_object;

	// runtime must be placed into a state enabling to allocate zero-copy buffers.
	checkCuda(cudaSetDeviceFlags(cudaDeviceMapHost));

	// init pinned memory
	checkCuda(
			cudaHostAlloc((void**) &host_object, sizeof(MyClass),
					cudaHostAllocWriteCombined | cudaHostAllocMapped));

	printf("Host object value: %d\n", host_object->getValue());

	checkCuda(cudaHostGetDevicePointer(&device_object, host_object, 0));

	// initialize cuPrintf
	cudaPrintfInit();

	// launch a kernel with a single thread
	device_method<<<1, 1>>>(device_object);

	// display the device's output
	cudaPrintfDisplay();
	// clean up after cuPrintf
	cudaPrintfEnd();

	printf("Host object value: %d (after gpu execution)\n",
			host_object->getValue());

	return 0;
}
