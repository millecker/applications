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
#include <assert.h>

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
	//pthread_mutex_t mutex_result_available;
	//pthread_mutex_t mutex_has_task;
	//pthread_cond_t new_command_cv;
	volatile int param1;
	volatile bool result_available;
	volatile int result_int;
	//string resultString;

public:
	volatile MESSAGE_TYPE command;
	volatile bool has_task;
	volatile bool task_accepted;
	volatile int lock_thread_id;
	volatile bool done;
	volatile bool is_monitoring;

	KernelWrapper() {
		init();
	}
	~KernelWrapper() {
	}

	void init() {
		printf("KernelWrapper init\n");

		is_monitoring = false;
		done = false;
		//pthread_mutex_init(&mutex_has_task, NULL);

		reset();
	}

	void startMonitor() {
		printf("KernelWrapper startMonitor\n");
		pthread_create(&t_monitor, NULL, &KernelWrapper::thread, this);
	}

	static void *thread(void *context) {
		KernelWrapper *_this = ((KernelWrapper *) context);

		while (!_this->done) {
			_this->is_monitoring = true;
			//printf(
			//		"KernelWrapper MonitorThread has_task: %s, task_accepted: %s, lock_thread_id: %d, command: %d\n",
			//		(_this->has_task) ? "true" : "false",
			//		(_this->task_accepted) ? "true" : "false",
			//		_this->lock_thread_id, _this->command);

			if ((!_this->has_task) && (_this->lock_thread_id >= 0)
					&& (_this->command != UNDEFINED)) {
				_this->task_accepted = true;
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
			result_available = true;
			break;
		}

		}
	}

	__device__ __host__ void reset() {
		lock_thread_id = -1;
		command = UNDEFINED;
		has_task = false;
		task_accepted = false;
		result_available = false;
	}

	__device__ __host__ int getValue(int val) {

		// wait for wrapper thread to end
		while (has_task) {
		}

		command = GET_VALUE;
		param1 = val;
		has_task = true;
		//__threadfence_system();

		// wait for wrapper thread to accept
		while (!task_accepted) {
		}

		// wait for socket communication to end
		while (!result_available) {
		}

		reset();
		return result_int;
	}

	__device__ __host__ void sendDone() {
		command = DONE;
		has_task = true;

		// wait for wrapper thread to accept
		while (!task_accepted) {
		}

		printf(
				"KernelWrapper::sendDone: has_task: %s, task_accepted: %s, lock_thread_id: %d, command: %d\n",
				(has_task) ? "true" : "false",
				(task_accepted) ? "true" : "false", lock_thread_id, command);

		// wait for socket communication to end
		while (!result_available) {
		}

		reset();
		done = true;
		return;
	}
};

KernelWrapper *h_kernelWrapper;

void sigint_handler(int s) {
	printf("Caught signal %d\n", s);

	if (h_kernelWrapper->is_monitoring) {
		h_kernelWrapper->lock_thread_id = 0;
		h_kernelWrapper->sendDone();

		pthread_join(t_socket_server, NULL);
	}
	exit(0);
}

// Convenience function for checking CUDA runtime API results
// can be wrapped around any runtime API call. No-op in release builds.
inline cudaError_t checkCuda(cudaError_t result) {
	if (result != cudaSuccess) {
		fprintf(stderr, "CUDA Runtime Error: %s\n", cudaGetErrorString(result));
		assert(result == cudaSuccess);
	}
	return result;
}

__global__ void device_method(KernelWrapper *d_kernelWrapper) {

	int thread_id = threadIdx.x + blockIdx.x * blockDim.x;

	int old;
	int count = 0;

	while (count < 100) {
		old = atomicCAS((int *) &d_kernelWrapper->lock_thread_id, -1,
				thread_id);
		if (old == -1 || old == thread_id) {
			//do critical section code

			cuPrintf("Thread %d active_thread_id: %d\n", thread_id,
					d_kernelWrapper->lock_thread_id);

			int val = d_kernelWrapper->getValue(thread_id);

		} else {
			count++;
			if (count > 50) {
				count = 0;
			}
		}
	}

	/*
	 bool accessed = false;

	 // getValue for each Thread
	 do {

	 // wait for possible old task
	 while (d_kernelWrapper->has_task) {
	 }

	 atomicExch((int *) &d_kernelWrapper->active_thread_id, thread_id);
	 //__threadfence_system();

	 if (d_kernelWrapper->active_thread_id == thread_id) {
	 accessed = true;
	 cuPrintf("Thread %d active_thread_id: %d\n", thread_id,
	 d_kernelWrapper->active_thread_id);

	 cuPrintf("Thread %d task_accepted: %s\n", thread_id,
	 (d_kernelWrapper->task_accepted) ? "true" : "false");

	 d_kernelWrapper->has_task = true;
	 int val = d_kernelWrapper->getValue(thread_id);
	 d_kernelWrapper->has_task = false;

	 cuPrintf("Thread %d getValue: %d\n", thread_id, val);
	 }

	 syncthreads();

	 } while (!accessed);
	 */
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

	// wait for kernelWrapper monitoring
	while (!h_kernelWrapper->is_monitoring) {
	}
	printf("KernelWrapper is_monitoring: %s\n",
			(h_kernelWrapper->is_monitoring) ? "true" : "false");

	// test call host_kernelWrapper
	//h_kernelWrapper->active_thread_id = 0;
	//int value = h_kernelWrapper->getValue(0);
	//printf("Host h_kernelWrapper getValue: %d\n", value);

	KernelWrapper *d_kernelWrapper;
	checkCuda(cudaHostGetDevicePointer(&d_kernelWrapper, h_kernelWrapper, 0));

	// initialize cuPrintf
	cudaPrintfInit();

	//device_method<<<4, 1>>>(d_kernelWrapper);

	// display the device's output
	cudaPrintfDisplay();
	// clean up after cuPrintf
	cudaPrintfEnd();

	h_kernelWrapper->lock_thread_id = 0;
	h_kernelWrapper->sendDone();

	// wait for SocketServer
	pthread_join(t_socket_server, NULL);

	return 0;
}
