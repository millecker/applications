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
#include "Mutex.hh"
#include <pthread.h>

Mutex::Mutex() {
	//create mutex attribute variable
	pthread_mutexattr_t mAttr;

	// setup recursive mutex for mutex attribute
	pthread_mutexattr_settype(&mAttr, PTHREAD_MUTEX_RECURSIVE);

	// Use the mutex attribute to create the mutex
	pthread_mutex_init(&m_criticalSection, &mAttr);

	// Mutex attribute can be destroy after initializing the mutex variable
	pthread_mutexattr_destroy(&mAttr);
}

Mutex::~Mutex() {
	pthread_mutex_destroy(&m_criticalSection);
}

void Mutex::lock() {
	pthread_mutex_lock(&m_criticalSection);
}

void Mutex::unlock() {
	pthread_mutex_unlock(&m_criticalSection);
}
