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
	pthread_mutex_t mutex_process_command;
	//pthread_mutex_t mutex_has_task;

	volatile int param1;
	volatile bool result_available;
	volatile int result_int;
	//volatile string resultString;

public:
	volatile MESSAGE_TYPE command;
	volatile bool has_task;
	volatile int lock_thread_id;
	volatile bool done;
	volatile bool is_monitoring;

	KernelWrapper() {
		init();
	}
	~KernelWrapper() {
		pthread_mutex_destroy(&mutex_process_command);
	}

	void init() {
		printf("KernelWrapper init\n");

		is_monitoring = false;
		done = false;
		has_task = false;

		result_available = false;
		result_int = 0;

		lock_thread_id = -1;

		pthread_mutex_init(&mutex_process_command, NULL);

		reset();
	}

	void reset() volatile {
		command = UNDEFINED;
		has_task = false;
		printf("KernelWrapper reset lock_thread_id: %d, has_task: %s\n",
				lock_thread_id, (has_task) ? "true" : "false");
	}

	void start_monitoring() {
		printf("KernelWrapper start monitor thread\n");
		pthread_create(&t_monitor, NULL, &KernelWrapper::thread, this);
	}

	static void *thread(void *context) {
		volatile KernelWrapper *_this = ((KernelWrapper *) context);

		while (!_this->done) {
			_this->is_monitoring = true;
			//printf(
			//		"KernelWrapper MonitorThread has_task: %s, lock_thread_id: %d, command: %d\n",
			//		(_this->has_task) ? "true" : "false",
			//		_this->lock_thread_id, _this->command);

			if ((_this->has_task) && (_this->lock_thread_id >= 0)
					&& (_this->command != UNDEFINED)) {

				pthread_mutex_t *lock =
						(pthread_mutex_t *) &_this->mutex_process_command;
				int result = pthread_mutex_trylock(lock);
				printf(
						"KernelWrapper thread: %p, trylock(mutex_process_command): %d mutex: %p\n",
						pthread_self(), result, lock);

				if (result != 0) {
					pthread_mutex_lock(lock);
				}
				_this->processCommand();
				_this->reset();
				pthread_mutex_unlock(lock);
				printf(
						"KernelWrapper thread: %p, UNLOCKED(mutex_process_command)\n",
						pthread_self());

			}
		}
		return NULL;
	}

	void processCommand() volatile {

		printf(
				"KernelWrapper processCommand: %d, lock_thread_id: %d, result_available: %s\n",
				command, lock_thread_id, (result_available) ? "true" : "false");

		switch (command) {

		case GET_VALUE: {
			socket_client.sendCMD(GET_VALUE, param1);
			while (!socket_client.isNewResultInt) {
				socket_client.nextEvent();
			}
			socket_client.isNewResultInt = false;

			result_int = socket_client.resultInt;
			result_available = true;

			printf("KernelWrapper got result: %d result_available: %s\n",
					result_int, (result_available) ? "true" : "false");

			// block until result was consumed
			while (result_available) {
				printf(
						"KernelWrapper wait for consuming result! result_int: %d, result_available: %s\n",
						result_int, (result_available) ? "true" : "false");

			}

			printf("KernelWrapper consumed result: %d\n", result_int);

			break;
		}
		case DONE: {
			socket_client.sendCMD(DONE);
			result_available = true;
			// block until result was consumed
			while (result_available) {
			}

			break;
		}

		}
	}

	// Host Method
	int getValue(int thread_id, int val) {

		while (has_task) {
		}
		lock_thread_id = thread_id;

		printf("\ngetValue has_task=false lock_thread_id: %d val: %d\n",
				lock_thread_id, val);

		command = GET_VALUE;
		param1 = val;
		has_task = true;

		while (!result_available) {
		}
		result_available = false;

		return result_int;
	}

	// Device Method
	// lock_thread_id was already set
	__device__ int getValue(int val) {

		while (has_task) {
		}
		cuPrintf("getValue lock_thread_id: %d val: %d\n", lock_thread_id, val);

		command = GET_VALUE;
		param1 = val;
		has_task = true;
		//__threadfence_system();

		// wait for socket communication to end
		int count = 0;
		while (!result_available) {
			//cuPrintf(
			//		"getValue wait for socket communication to end! lock_thread_id: %d\n",
			//		 lock_thread_id);
			if (++count > 10000) {
				cuPrintf(
						"getValue TIMEOUT wait for socket communication to end! lock_thread_id: %d\n",
						lock_thread_id);
				break;
			}
		}
		result_available = false;
		cuPrintf("getValue lock_thread_id: %d result_available: %s\n",
				lock_thread_id, (result_available) ? "true" : "false");

		return result_int;
	}

	__device__ __host__ void sendDone() {

		command = DONE;
		has_task = true;
		//__threadfence_system();

		//printf(
		//		"KernelWrapper::sendDone: has_task: %s, lock_thread_id: %d, command: %d\n",
		//		(has_task) ? "true" : "false", lock_thread_id, command);

		// wait for socket communication to end
		while (!result_available) {
		}

		done = true;
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
	int count = 0;

	while (count < 100) {
		// (old == -1 ? thread_id : old)
		int old = atomicCAS((int *) &d_kernelWrapper->lock_thread_id, -1,
				thread_id);

		cuPrintf("Thread %d old: %d\n", thread_id, old);

		if (old == -1 || old == thread_id) {
			//do critical section code
			// thread won race condition

			cuPrintf("Thread %d lock_thread_id: %d\n", thread_id,
					d_kernelWrapper->lock_thread_id);

			int val = d_kernelWrapper->getValue(thread_id);
			cuPrintf("Thread %d getValue: %d\n", thread_id, val);

			d_kernelWrapper->lock_thread_id = -1;
			__threadfence();
			// exit infinite loop
			break;

		} else {
			count++;
			if (count > 50) {
				count = 0;
			}
		}
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

	//CUDA setup
	// runtime must be placed into a state enabling to allocate zero-copy buffers.
	checkCuda(cudaSetDeviceFlags(cudaDeviceMapHost));

	// allocate host_kernelWrapper as pinned memory
	checkCuda(
			cudaHostAlloc((void**) &h_kernelWrapper, sizeof(KernelWrapper),
					cudaHostAllocWriteCombined | cudaHostAllocMapped));

	// init host_kernelWrapper
	h_kernelWrapper->init();
	h_kernelWrapper->start_monitoring();

	// wait for kernelWrapper monitoring
	while (!h_kernelWrapper->is_monitoring) {
	}
	printf("KernelWrapper is_monitoring: %s\n",
			(h_kernelWrapper->is_monitoring) ? "true" : "false");

	// test call host_kernelWrapper getValue
	printf("\n\nTEST KernelWrapper using getValue host method!\n");
	for (int i = 0; i < 5; i++) {
		int rand_value = rand() % 100; //in the range 0 to 99;
		int value = h_kernelWrapper->getValue(i, rand_value);
		printf("Host h_kernelWrapper[%d] getValue: %d\n", i, value);
		assert(rand_value+1 == value);
	}
	sleep(2);

	KernelWrapper *d_kernelWrapper;
	checkCuda(cudaHostGetDevicePointer(&d_kernelWrapper, h_kernelWrapper, 0));

	// initialize cuPrintf
	cudaPrintfInit();

	device_method<<<2, 1>>>(d_kernelWrapper);

	// display the device's output
	printf("\n\nTEST KernelWrapper using getValue device method!\n");
	cudaPrintfDisplay();
	// clean up after cuPrintf
	cudaPrintfEnd();

	printf("\n\nSend DONE and Exit\n");
	// Send DONE to SocketServer
	h_kernelWrapper->lock_thread_id = 0;
	h_kernelWrapper->sendDone();

	// wait for SocketServer
	pthread_join(t_socket_server, NULL);

	return 0;
}
