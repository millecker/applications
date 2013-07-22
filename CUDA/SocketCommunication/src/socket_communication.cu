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

#include "socket/MessageType.hh"
#include "socket/SocketServer.hh"
#include "socket/SocketClient.hh"

#include <stdio.h>
#include <stdlib.h>

#include <signal.h>
#include <pthread.h>

#include <cuda_runtime.h>

// Global vars
pthread_t t_socket_server;
SocketServer socket_server;
SocketClient socket_client;

class KernelWrapper {
private:
	MESSAGE_TYPE command;
	int param1;
	bool result_available;
	int resultInt;
	//string resultString;
public:
	int active_thread_id;

	KernelWrapper() {
		active_thread_id = -1;
		result_available = false;
	}
	~KernelWrapper() {
	}

	__device__ __host__ int getValue(int val) {

		command = GET_NEXT_VALUE;
		param1 = val;

		while (!result_available) {
		}

		//d_object->setValue(++val);
		//__threadfence();

		return resultInt;
	}

	__device__ __host__ void done() {
		command = DONE;
	}
};

void sigint_handler(int s) {
	printf("Caught signal %d\n", s);

	socket_client.sendDone();

	pthread_join(t_socket_server, NULL);
	exit(0);
}

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

__global__ void device_method(KernelWrapper *d_kernelWrapper) {

	int thread_id = threadIdx.x + blockIdx.x * blockDim.x;
	atomicExch(&d_kernelWrapper->active_thread_id, thread_id);

	if (d_kernelWrapper->active_thread_id == thread_id) {

		int val = d_kernelWrapper->getValue(thread_id);
		cuPrintf("Device object value: %d\n", val);
	}
}

int main(void) {

	// register SIGINT (STRG-C) handler
	struct sigaction sigIntHandler;
	sigIntHandler.sa_handler = sigint_handler;
	sigemptyset(&sigIntHandler.sa_mask);
	sigIntHandler.sa_flags = 0;
	sigaction(SIGINT, &sigIntHandler, NULL);

	// start SocketServer
	pthread_create(&t_socket_server, NULL, &SocketServer::thread,
			&socket_server);

	// wait for SocketServer to come up
	while (socket_server.getPort() == -1) {
	}

	// connect SocketClient
	socket_client.connectSocket(socket_server.getPort());

	int value = socket_client.getNextValue(0);
	printf("Host client getNextValue: %d\n", value);

	//CUDA setup
	// runtime must be placed into a state enabling to allocate zero-copy buffers.
	checkCuda(cudaSetDeviceFlags(cudaDeviceMapHost));

	KernelWrapper *h_kernelWrapper;
	KernelWrapper *d_kernelWrapper;

	// init host socket client as pinned memory
	checkCuda(
			cudaHostAlloc((void**) &h_kernelWrapper, sizeof(KernelWrapper),
					cudaHostAllocWriteCombined | cudaHostAllocMapped));

	checkCuda(cudaHostGetDevicePointer(&d_kernelWrapper, h_kernelWrapper, 0));

	// initialize cuPrintf
	cudaPrintfInit();

	//device_method<<<1, 1>>>(d_kernelWrapper);

	// display the device's output
	cudaPrintfDisplay();
	// clean up after cuPrintf
	cudaPrintfEnd();

	//printf("Host object value: %d (after gpu execution) (thread_num=%d)\n",
	//		host_client->getValue(), 16 * 4);

	//assert(host_client->getValue() == 16*4);

	sleep(2);

	socket_client.sendDone();
	// wait for SocketServer
	pthread_join(t_socket_server, NULL);

	return 0;
}
