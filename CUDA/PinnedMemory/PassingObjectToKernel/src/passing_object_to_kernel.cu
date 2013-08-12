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
#include "util/cuPrintf.cu"
#include <cuda_runtime.h>

class MyClass {
public:
	volatile int value;
	volatile int lock_thread_id;

	__device__ __host__ MyClass() {
		value = 0;
		lock_thread_id = -1;
	}
	__device__ __host__ MyClass(int v) {
		value = v;
	}
	__device__ __host__ void setValue(int v) {
		value = v;
	}
	__device__ __host__ int getValue() {
		return value;
	}
	__device__ __host__ ~MyClass() {
	}
};

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

	int thread_id = threadIdx.x + blockIdx.x * blockDim.x;
	int count = 0;
	int timeout = 0;
	bool done = false;

	while (count < 100) {

		if (++timeout > 100000) {
			//cuPrintf(
			//		"device_method TIMEOUT! thread_id: %d, lock_thread_id: %d\n",
			//		thread_id, d_object->lock_thread_id);
			break;
		}

		__syncthreads();
		if (done) {
			break;
		}

		int old = atomicCAS((int *) &d_object->lock_thread_id, -1, thread_id);

		if (old == -1 || old == thread_id) {

			// Atomic block begin - critical section
			//cuPrintf("Thread %d GOT LOCK lock_thread_id: %d\n", thread_id,
			//		d_object->lock_thread_id);

			int val = d_object->getValue();
			//cuPrintf("Device object value: %d\n", val);
			d_object->setValue(++val);

			//atomicExch((int *) &d_object->lock_thread_id, -1);
			d_object->lock_thread_id = -1;

			//__threadfence_system();
			//__threadfence_block();
			//__threadfence();
			//threadfence();

			done = true; // finished work

			//cuPrintf("Thread %d LEAVE LOCK lock_thread_id: %d\n", thread_id,
			//		d_object->lock_thread_id);

			// Atomic block end - critical section

		} else {
			//cuPrintf("Thread %d lock_thread_id: %d\n", thread_id, old);

			count++;
			if (count > 50) {
				count = 0;
			}
		}
	}

}

int main(void) {

	/*
	 Total number of registers available per block: 65536
	 Warp size:                                     32
	 Maximum number of threads per multiprocessor:  2048
	 Maximum number of threads per block:           1024
	 Maximum sizes of each dimension of a block:    1024 x 1024 x 64
	 Maximum sizes of each dimension of a grid:     2147483647 x 65535 x 65535
	 */
	int blocks = 10; //65535;
	int threads = 1024;

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

	// init value and lock
	host_object->setValue(0);
	host_object->lock_thread_id = -1;

	printf("Host object value: %d\n", host_object->getValue());

	checkCuda(cudaHostGetDevicePointer(&device_object, host_object, 0));

	// initialize cuPrintf
	cudaPrintfInit();

	// create cuda event handles
	cudaEvent_t start, stop;
	checkCuda(cudaEventCreate(&start));
	checkCuda(cudaEventCreate(&stop));
	float gpu_time = 0.0f;

	cudaEventRecord(start, 0);
	// blocks, threads
	// add<<<(N + M -1) / M, M>>>(d_a,d_b,d_c,N);
	device_method<<<blocks, threads>>>(device_object);

	cudaEventRecord(stop, 0);

	// display the device's output
	cudaPrintfDisplay();
	// clean up after cuPrintf
	cudaPrintfEnd();

	checkCuda(cudaEventElapsedTime(&gpu_time, start, stop));
	printf("time spent executing by the GPU: %.2f\n", gpu_time);
	printf("Host object value: %d (after gpu execution) (thread_num=%d)\n",
			host_object->getValue(), blocks * threads);

	assert(host_object->getValue() == blocks * threads);

	return 0;
}
