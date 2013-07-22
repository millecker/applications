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
	pthread_t t_monitor;
	pthread_mutex_t mutex_result_available;
	pthread_mutex_t mutex_has_task;
	//pthread_cond_t new_command_cv;
	int param1;
	bool result_available;
	int result_int;
	//string resultString;

public:
	MESSAGE_TYPE command;
	bool has_task;
	int active_thread_id;
	bool done;

	KernelWrapper() {
		init();
	}
	~KernelWrapper() {
	}

	void startMonitor() {
		pthread_create(&t_monitor, NULL, &KernelWrapper::thread, this);
	}

	static void *thread(void *context) {
		KernelWrapper *_this = ((KernelWrapper *) context);

		while (!_this->done) {
			if ((!_this->has_task) && (_this->active_thread_id >= 0)
					&& (_this->command != UNDEFINED)) {
				_this->has_task = true;
				_this->sendCommand();
			}
		}
		return NULL;
	}

	void sendCommand() {

		switch (command) {

		case GET_VALUE: {
			socket_client.sendCMD(GET_VALUE, param1);
			while (!socket_client.isNewResultInt) {
				socket_client.nextEvent();
			}
			result_int = socket_client.resultInt;
			socket_client.isNewResultInt = false;
			result_available = true;
			break;
		}
		case DONE: {
			socket_client.sendCMD(DONE);
			break;
		}

		}
	}

	__device__ __host__ void init() {
		active_thread_id = -1;
		command = UNDEFINED;
		has_task = false;
		result_available = false;
		done = false;
	}

	__device__ __host__ int getValue(int val) {

		command = GET_VALUE;
		param1 = val;

		// wait for socket communication
		while (true) {
			if (result_available) {
				break;
			}
		}

		init();
		return result_int;
	}

	__device__ __host__ void sendDone() {
		command = DONE;
		//init();
	}
};

KernelWrapper *h_kernelWrapper;

void sigint_handler(int s) {
	printf("Caught signal %d\n", s);

	h_kernelWrapper->active_thread_id = 0;
	h_kernelWrapper->sendDone();
	h_kernelWrapper->done = true;

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
	bool accessed = false;

	// getValue for each Thread
	do {
		atomicExch(&d_kernelWrapper->active_thread_id, thread_id);

		if (d_kernelWrapper->active_thread_id == thread_id) {
			accessed = true;
			cuPrintf("Thread %d active_thread_id: %d\n", thread_id,
					d_kernelWrapper->active_thread_id);

			int val = d_kernelWrapper->getValue(thread_id);
			cuPrintf("Thread %d getValue: %d\n", thread_id, val);
		}

		syncthreads();

	} while (!accessed);
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

	//CUDA setup
	// runtime must be placed into a state enabling to allocate zero-copy buffers.
	checkCuda(cudaSetDeviceFlags(cudaDeviceMapHost));

	// allocate host_kernelWrapper as pinned memory
	checkCuda(
			cudaHostAlloc((void**) &h_kernelWrapper, sizeof(KernelWrapper),
					cudaHostAllocWriteCombined | cudaHostAllocMapped));

	// init host_kernelWrapper
	h_kernelWrapper->init();
	h_kernelWrapper->startMonitor();

	// test call host_kernelWrapper
	//h_kernelWrapper->active_thread_id = 0;
	//int value = h_kernelWrapper->getValue(0);
	//printf("Host h_kernelWrapper getValue: %d\n", value);

	KernelWrapper *d_kernelWrapper;
	checkCuda(cudaHostGetDevicePointer(&d_kernelWrapper, h_kernelWrapper, 0));

	// initialize cuPrintf
	cudaPrintfInit();

	device_method<<<4, 1>>>(d_kernelWrapper);

	// display the device's output
	cudaPrintfDisplay();
	// clean up after cuPrintf
	cudaPrintfEnd();

	//printf("Host object value: %d (after gpu execution) (thread_num=%d)\n",
	//		host_client->getValue(), 16 * 4);

	//assert(host_client->getValue() == 16*4);

	sleep(2);

	h_kernelWrapper->active_thread_id = 0;
	h_kernelWrapper->sendDone();
	h_kernelWrapper->done = true;
	// wait for SocketServer
	pthread_join(t_socket_server, NULL);

	return 0;
}
