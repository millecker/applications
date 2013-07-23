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
#include "Lock.hh"
#include "Mutex.hh"

Lock::Lock(Mutex &mutex) :
		m_mutex(mutex), m_locked(true) {
	mutex.lock();
}

Lock::~Lock() {
	m_mutex.unlock();
}

//report the state of locking when used as a boolean
Lock::operator bool() const {
	return m_locked;
}

//unlock
void Lock::setUnlock() {
	m_locked = false;
}
