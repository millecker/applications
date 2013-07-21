/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "MessageType.hh"
#include "SocketServer.hh"

#include "../hadoop/SerialUtils.hh"

#include <sys/socket.h>
#include <netinet/in.h>
#include <errno.h>

SocketServer::SocketServer() {
	sock = -1;
	port = -1;
	done = false;
}

SocketServer::~SocketServer() {
	fflush(stdout);
	if (sock != -1) {
		int result = shutdown(sock, SHUT_RDWR);
		//if (result != 0) {
		//	fprintf(stderr, "SocketServer: problem shutting socket\n");
		//}
		result = close(sock);
		if (result != 0) {
			fprintf(stderr, "SocketServer: problem closing socket\n");
		}
	}
}

int SocketServer::getPort() {
	return port;
}

void *SocketServer::runSocketServer() {
	printf("SocketServer started!\n");

	sock = socket(PF_INET, SOCK_STREAM, 0);
	if (sock < 0) {
		fprintf(stderr, "SocketServer: problem creating socket: %s\n",
				strerror(errno));
	}

	sockaddr_in addr;
	memset((char *) &addr, 0, sizeof(addr));
	addr.sin_family = AF_INET;
	// bind to a OS-assigned random port.
	addr.sin_port = htons(0);
	addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);

	int ret = bind(sock, (sockaddr*) &addr, sizeof(addr));
	if (ret < 0) {
		fprintf(stderr, "SocketServer: error on binding: %s\n",
				strerror(errno));
		return NULL;
	}

	// Get current port
	struct sockaddr_in current_addr;
	int current_addr_len = sizeof(current_addr);
	ret = getsockname(sock, (sockaddr*) &current_addr,
			(socklen_t *) &current_addr_len);
	if (ret < 0) {
		fprintf(stderr, "SocketServer: problem getsockname: %s\n",
				strerror(errno));
		return NULL;
	}
	port = ntohs(current_addr.sin_port);

	listen(sock, 1);

	printf("SocketServer is running @ port %d ...\n", port);

	sockaddr_in partnerAddr;
	int adrLen;
	int clientSock = accept(sock, (sockaddr*) &partnerAddr,
			(socklen_t *) &adrLen);

	printf("SocketServer: Client connected.\n");

	FILE* in_stream = fdopen(clientSock, "r");
	FILE* out_stream = fdopen(clientSock, "w");
	HadoopUtils::FileInStream* inStream = new HadoopUtils::FileInStream();
	HadoopUtils::FileOutStream* outStream = new HadoopUtils::FileOutStream();
	inStream->open(in_stream);
	outStream->open(out_stream);

	while (!done) {

		printf("SocketServer: wait for next command!\n");
		int32_t cmd = HadoopUtils::deserializeInt(*inStream);

		switch (cmd) {

		case GET_NEXT_VALUE: {
			int32_t val = HadoopUtils::deserializeInt(*inStream);
			HadoopUtils::serializeInt(val + 1, *outStream);
			outStream->flush();
			printf("SocketServer - GET_NEXT_VALUE IN=%d OUT=%d\n", val,
					val + 1);
			break;
		}
		case DONE: {
			printf("SocketServer - DONE\n");
			done = true;
			break;
		}

		default:
			fprintf(stderr, "SocketServer - Unknown binary command: %d\n", cmd);
			break;
		}
	}

	inStream->close();
	outStream->close();
	close(clientSock);

	delete inStream;
	delete outStream;

	printf("SocketServer stopped!\n");
	pthread_exit(0);
}
