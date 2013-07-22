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
#ifndef SOCKET_CLIENT_TYPE_HH
#define SOCKET_CLIENT_TYPE_HH

#include "../hadoop/SerialUtils.hh"

using std::string;

class SocketClient {
private:
	int sock;
	FILE* in_stream;
	FILE* out_stream;
	HadoopUtils::FileInStream* inStream;
	HadoopUtils::FileOutStream* outStream;

public:
	int32_t resultInt;
	bool isNewResultInt;
	int64_t resultLong;
	bool isNewResultLong;
	string resultString;
	bool isNewResultString;
	//vector<string> resultVector;
	//bool isNewResultVector;
	//bool isNewKeyValuePair;
	//string currentKey;
	//string currentValue;

	SocketClient();
	~SocketClient();
	void connectSocket(int port);
	void sendCMD(int32_t cmd);
	void sendCMD(int32_t cmd, int32_t value);
	void sendCMD(int32_t cmd, int32_t value, const string values[], int size);
	void sendCMD(int32_t cmd, const string& value);
	void sendCMD(int32_t cmd, const string values[], int size);
	void nextEvent();
};

#endif
