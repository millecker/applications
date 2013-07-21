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
#include "SocketClient.hh"
#include "MessageType.hh"

#include "../hadoop/SerialUtils.hh"

#include <sys/socket.h>
#include <netinet/in.h>
#include <errno.h>

SocketClient::SocketClient() {
	sock = -1;
	in_stream = NULL;
	out_stream = NULL;
}

void SocketClient::connectSocket(int port) {
	printf("SocketClient started\n");

	if (port <= 0) {
		printf("SocketClient: invalid port number!\n");
		return; /* Failed */
	}

	sock = socket(PF_INET, SOCK_STREAM, 0);
	if (sock == -1) {
		fprintf(stderr, "SocketClient: problem creating socket: %s\n",
				strerror(errno));
	}

	sockaddr_in addr;
	addr.sin_family = AF_INET;
	addr.sin_port = htons(port);
	addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);

	int res = connect(sock, (sockaddr*) &addr, sizeof(addr));
	if (res != 0) {
		fprintf(stderr, "SocketClient: problem connecting command socket: %s\n",
				strerror(errno));
	}

	in_stream = fdopen(sock, "r");
	out_stream = fdopen(sock, "w");

	inStream = new HadoopUtils::FileInStream();
	inStream->open(in_stream);
	outStream = new HadoopUtils::FileOutStream();
	outStream->open(out_stream);

	printf("SocketClient is connected to port %d ...\n", port);
}

SocketClient::~SocketClient() {
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
			fprintf(stderr, "SocketClient: problem shutting down socket\n");
		}
		result = close(sock);
		if (result != 0) {
			fprintf(stderr, "SocketClient: problem closing socket\n");
		}
	}
}

int SocketClient::getNextValue(int val) {

	HadoopUtils::serializeInt(GET_NEXT_VALUE, *outStream);
	HadoopUtils::serializeInt(val, *outStream);
	outStream->flush();

	int return_val = HadoopUtils::deserializeInt(*inStream);

	printf("SocketClient sent GET_NEXT_VALUE OUT=%d IN=%d\n", val, return_val);

	return return_val;
}

void SocketClient::sendDone() {

	HadoopUtils::serializeInt(DONE, *outStream);
	outStream->flush();
	printf("SocketClient sent DONE\n");
}
