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

#include "hadoop/SerialUtils.hh"
#include "hadoop/StringUtils.hh"

#include <stdio.h>
#include <assert.h>
#include <errno.h>

#include <sys/types.h>
#include <netinet/in.h>

#include <sys/socket.h>
#include <iostream>
#include <fstream>

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <pthread.h>

#include <cuda_runtime.h>

#define SERVER_SOCKET_PORT 1234

/********************************************/
/***************    Server    ***************/
/********************************************/
class SocketServer {
private:
	int sock;
	bool done;

public:
	SocketServer() {
		sock = -1;
		done = false;

		sock = socket(PF_INET, SOCK_STREAM, 0);
		if (sock == -1) {
			fprintf(stderr, "problem creating socket: %s\n", strerror(errno));
		}

		sockaddr_in addr;
		addr.sin_family = AF_INET;
		addr.sin_port = htons(SERVER_SOCKET_PORT);
		addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);

		bind(sock, (sockaddr*) &addr, sizeof(addr));
		listen(sock, 5);
		printf("SocketServer is running @ port %d ...\n", SERVER_SOCKET_PORT);

		do {
			sockaddr_in partnerAddr;
			int adrLen;
			int partnerSock = accept(sock, (sockaddr*) &partnerAddr, (socklen_t *)&adrLen);

			//HadoopUtils::FileInStream* inStream;
			//HadoopUtils::FileOutStream* outStream;

			//int32_t cmd;
			//cmd = HadoopUtils::deserializeInt(*inStream);

			//MsgLen = recv(IDPartnerSocket, Puffer, MAXPUF, 0);
			/* tu was mit den Daten */
			//send(IDPartnerSocket, Puffer, MsgLen, 0);
			close(partnerSock);

		} while (!done);

	}

	~SocketServer() {
		fflush(stdout);
		if (sock != -1) {
			int result = shutdown(sock, SHUT_RDWR);
			if (result != 0) {
				fprintf(stderr, "problem shutting socket");
			}
			result = close(sock);
			if (result != 0) {
				fprintf(stderr, "problem closing socket");
			}
		}
	}
};

/********************************************/
/**************     CLIENT     **************/
/********************************************/
class SocketClient {
private:
	int sock;
	FILE* in_stream;
	FILE* out_stream;
	HadoopUtils::FileInStream* inStream;
	HadoopUtils::FileOutStream* outStream;

public:
	int value;

	SocketClient() {
		sock = -1;
		in_stream = NULL;
		out_stream = NULL;

		sock = socket(PF_INET, SOCK_STREAM, 0);
		if (sock == -1) {
			fprintf(stderr, "problem creating socket: %s\n", strerror(errno));
		}

		sockaddr_in addr;
		addr.sin_family = AF_INET;
		addr.sin_port = htons(SERVER_SOCKET_PORT);
		addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);

		int res = connect(sock, (sockaddr*) &addr, sizeof(addr));
		if (res != 0) {
			fprintf(stderr, "problem connecting command socket: %s\n",
					strerror(errno));
		}

		in_stream = fdopen(sock, "r");
		out_stream = fdopen(sock, "w");

		inStream = new HadoopUtils::FileInStream();
		inStream->open(in_stream);
		outStream = new HadoopUtils::FileOutStream();
		outStream->open(out_stream);

		printf("SocketClient is connect to port %d ...\n", SERVER_SOCKET_PORT);
	}

	~SocketClient() {
		if (in_stream != NULL) {
			fflush(in_stream);
		}
		if (out_stream != NULL) {
			fflush(out_stream);
		}
		fflush(stdout);
		if (sock != -1) {
			int result = shutdown(sock, SHUT_RDWR);
			if (result != 0) {
				fprintf(stderr, "problem shutting socket");
			}
			result = close(sock);
			if (result != 0) {
				fprintf(stderr, "problem closing socket");
			}
		}
	}

	__device__ __host__ void setValue(int v) {
		value = v;
	}
	__device__ __host__ int getValue() {
		return value;
	}
};

/********************************************/
/***************     CUDA     ***************/
/********************************************/

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

__global__ void device_method(SocketClient *d_object) {

	int val = d_object->getValue();
	cuPrintf("Device object value: %d\n", val);
	d_object->setValue(++val);
	__threadfence();
}

int main(void) {

	SocketServer *socketServer = new SocketServer();

	SocketClient *host_object;
	SocketClient *device_object;

	// runtime must be placed into a state enabling to allocate zero-copy buffers.
	checkCuda(cudaSetDeviceFlags(cudaDeviceMapHost));

	// init pinned memory
	checkCuda(
			cudaHostAlloc((void**) &host_object, sizeof(SocketClient),
					cudaHostAllocWriteCombined | cudaHostAllocMapped));

	// init value
	host_object->setValue(0);
	printf("Host object value: %d\n", host_object->getValue());

	checkCuda(cudaHostGetDevicePointer(&device_object, host_object, 0));

	// initialize cuPrintf
	cudaPrintfInit();

	device_method<<<16, 4>>>(device_object);

	// display the device's output
	cudaPrintfDisplay();
	// clean up after cuPrintf
	cudaPrintfEnd();

	printf("Host object value: %d (after gpu execution) (thread_num=%d)\n",
			host_object->getValue(), 16 * 4);

	assert(host_object->getValue() == 16*4);

	return 0;
}
