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

// Convenience function for checking CUDA runtime API results
// can be wrapped around any runtime API call. No-op in release builds.
inline cudaError_t checkCuda(cudaError_t result) {
  if (result != cudaSuccess) {
    fprintf(stderr, "CUDA Runtime Error: %s\n", cudaGetErrorString(result));
    assert(result == cudaSuccess);
  }
  return result;
}

class HostDeviceInterface {
public:
  // Only one thread is able to use the
  // HostDeviceInterface
  volatile int lock_thread_id; 

  // HostMonitor has_task
  volatile bool has_task;

  // HostMonitor is done (end of communication)
  volatile bool done;

  // Request for HostMonitor
  enum MESSAGE_TYPE {
	UNDEFINED, GET_VALUE, DONE
  };
  volatile MESSAGE_TYPE command;
  volatile int param1;

  // Response of HostMonitor
  volatile bool is_result_available;
  volatile int result_int;
  volatile string result_string;

  __device__ __host__ HostDeviceInterface() {
    lock_thread_id = -1;
    has_task = false;
    done = false;

    command = UNDEFINED;

    is_result_available = false;
    result_int = 0;
  }

  __device__ __host__ ~HostDeviceInterface() {}
};



class HostMonitor {
private:
  pthread_t monitor_thread;
  pthread_mutex_t mutex_process_command;
  //pthread_mutex_t mutex_result_available;

public:
  volatile bool is_monitoring;
  volatile HostDeviceInterface *host_device_interface;

  HostMonitor(HostDeviceInterface *h_d_interface) {
    host_device_interface = h_d_interface;
    printf("HostMonitor init\n");

    is_monitoring = false;

    pthread_mutex_init(&mutex_process_command, NULL);
    //pthread_mutex_init(&mutex_result_available, NULL);

    reset();
  }

  ~HostMonitor() {
    pthread_mutex_destroy(&mutex_process_command);
    //pthread_mutex_destroy(&mutex_result_available);
  }

  void reset() volatile {
    host_device_interface->command = HostDeviceInterface::UNDEFINED;
    host_device_interface->has_task = false;
    printf("HostMonitor reset lock_thread_id: %d, has_task: %s, result_available: %s\n",
           host_device_interface->lock_thread_id, 
           (host_device_interface->has_task) ? "true" : "false",
           (host_device_interface->is_result_available) ? "true" : "false");
  }

  void start_monitoring() {
    printf("HostMonitor start monitor thread\n");
    pthread_create(&monitor_thread, NULL, &HostMonitor::thread, this);
  }

  static void *thread(void *context) {
    volatile HostMonitor *_this = ((HostMonitor *) context);

    while (!_this->host_device_interface->done) {
      _this->is_monitoring = true;

      //printf("HostMonitor MonitorThread has_task: %s, lock_thread_id: %d, command: %d\n",
      //       (_this->has_task) ? "true" : "false",
      //       _this->lock_thread_id, _this->command);

      if ((_this->host_device_interface->has_task) && 
          (_this->host_device_interface->lock_thread_id >= 0) && 
          (_this->host_device_interface->command != HostDeviceInterface::UNDEFINED)) {

        pthread_mutex_t *lock = (pthread_mutex_t *) &_this->mutex_process_command;
	pthread_mutex_lock(lock);
	
        printf("HostMonitor thread: %p, LOCKED(mutex_process_command)\n", pthread_self());

	_this->processCommand();
        
        _this->reset();

	pthread_mutex_unlock(lock);
	printf("HostMonitor thread: %p, UNLOCKED(mutex_process_command)\n", pthread_self());
      }
    }
    return NULL;
  }

  void processCommand() volatile {

    printf("HostMonitor processCommand: %d, lock_thread_id: %d, result_available: %s\n",
           host_device_interface->command, 
           host_device_interface->lock_thread_id, 
           (host_device_interface->is_result_available) ? "true" : "false");

    switch (host_device_interface->command) {
      
      case HostDeviceInterface::GET_VALUE: {
        socket_client.sendCMD(HostDeviceInterface::GET_VALUE, host_device_interface->param1);
        
        while (!socket_client.isNewResultInt) {
          socket_client.nextEvent();
        }
        
        socket_client.isNewResultInt = false;
        host_device_interface->result_int = socket_client.resultInt;

        //pthread_mutex_lock((pthread_mutex_t *) &mutex_result_available);
        host_device_interface->is_result_available = true; // dieses statement zieht nicht!!!

        printf("HostMonitor got result: %d result_available: %s\n",
               host_device_interface->result_int, 
               (host_device_interface->is_result_available) ? "true" : "false");

	// block until result was consumed
	while (host_device_interface->is_result_available) {
          printf("HostMonitor wait for consuming result! result_int: %d, result_available: %s\n",
                 host_device_interface->result_int, 
                 (host_device_interface->is_result_available) ? "true" : "false");
	}
	
        //pthread_mutex_unlock((pthread_mutex_t *) &mutex_result_available);

	printf("HostMonitor consumed result: %d\n", host_device_interface->result_int);

        break;
      }

      case HostDeviceInterface::DONE: {
        socket_client.sendCMD(HostDeviceInterface::DONE);
        host_device_interface->is_result_available = true;
        // block until result was consumed
        while (host_device_interface->is_result_available) {}

        break;
      }
    }
  }

};

// global HostDeviceInterface for SIGINT handler
HostDeviceInterface *h_host_device_interface;
// global HostMonitor for SIGINT handler
HostMonitor *host_monitor;

// Host method
int getValue(int thread_id, int val) {
  // wait for possible old task to end
  while (h_host_device_interface->has_task) {}

  h_host_device_interface->lock_thread_id = thread_id;

  printf("\ngetValue has_task=false lock_thread_id: %d val: %d\n",
         h_host_device_interface->lock_thread_id, val);

  // Setup command
  h_host_device_interface->command = HostDeviceInterface::GET_VALUE;
  h_host_device_interface->param1 = val;
  h_host_device_interface->has_task = true;

  while (!h_host_device_interface->is_result_available) {}

  h_host_device_interface->is_result_available = false;
  h_host_device_interface->lock_thread_id = -1;

  return h_host_device_interface->result_int;
}

// Host method
void sendDone() {

  h_host_device_interface->command = HostDeviceInterface::DONE;
  h_host_device_interface->has_task = true;
		
  //printf("HOST::sendDone: has_task: %s, lock_thread_id: %d, command: %d\n",
  //       (has_task) ? "true" : "false", lock_thread_id, command);

  // wait for socket communication to end
  while (!h_host_device_interface->is_result_available) {}

  h_host_device_interface->done = true;
}


// Device method
__global__ void device_method(HostDeviceInterface *d_host_device_interface) {

  int thread_id = threadIdx.x + blockIdx.x * blockDim.x;
  int count = 0;
  int timeout = 0;
  bool done = false;

  while (count < 100) {

    if (++timeout > 100000) {
      cuPrintf("device_method TIMEOUT! lock_thread_id: %d\n",
               d_host_device_interface->lock_thread_id);
      break;
    }

    __syncthreads();
    
    if (done) {
      break;
    }

    // (lock_thread_id == -1 ? thread_id : lock_thread_id)
    int old = atomicCAS((int *) &d_host_device_interface->lock_thread_id, -1,
                        thread_id);

    // cuPrintf("Thread %d old: %d\n", thread_id, old);

    if (old == -1 || old == thread_id) {
      //do critical section code
      // thread won race condition

      cuPrintf("Thread %d GOT LOCK lock_thread_id: %d\n", thread_id,
               d_host_device_interface->lock_thread_id);


      //int val = d_kernelWrapper->getValue(thread_id);
      /************************************************************************/
      int val = thread_id;

      // do work
      int timeout = 0;
      // wait for possible old task to end
      while (d_host_device_interface->has_task) {
        if (++timeout > 10000) {
          cuPrintf("getValue TIMEOUT wait for old task to end! lock_thread_id: %d\n",
                   d_host_device_interface->lock_thread_id);
	  break;
	}
      }
		
      cuPrintf("getValue has_task=false lock_thread_id: %d val: %d\n",
               d_host_device_interface->lock_thread_id, val);

      // Setup command
      d_host_device_interface->command = HostDeviceInterface::GET_VALUE;
      d_host_device_interface->param1 = val;
      d_host_device_interface->has_task = true;
      __threadfence_system();
      //__threadfence();

      timeout = 0;
      // wait for socket communication to end
      while (!d_host_device_interface->is_result_available) {
        __threadfence_system();
        //__threadfence();
	
        //cuPrintf("getValue wait for socket communication to end! lock_thread_id: %d\n",
        //         lock_thread_id);
        
        if (++timeout > 30000) {
          cuPrintf("getValue TIMEOUT wait for socket communication to end! lock_thread_id: %d\n",
                   d_host_device_interface->lock_thread_id);
	  break;
        }
      }

      cuPrintf("getValue lock_thread_id: %d, result_int: %d, result_available: %s (before result_available = false)\n",
               d_host_device_interface->lock_thread_id, 
               d_host_device_interface->result_int, 
               (d_host_device_interface->is_result_available) ? "true" : "false");

      d_host_device_interface->is_result_available = false;
      __threadfence_system();
      //__threadfence();

      cuPrintf("getValue lock_thread_id: %d, result_int: %d, result_available: %s\n",
               d_host_device_interface->lock_thread_id, 
               d_host_device_interface->result_int, 
               (d_host_device_interface->is_result_available) ? "true" : "false");

      /************************************************************************/

      cuPrintf("Thread %d getValue: %d\n", thread_id, d_host_device_interface->result_int);

      d_host_device_interface->lock_thread_id = -1;
      //atomicExch((int *) &d_kernelWrapper, -1);

      __threadfence_system();
      //__threadfence();

      // exit infinite loop
      done = true; // finished work

    } else {
      count++;
      if (count > 50) {
        count = 0;
      }
    }
  }
}

void sigint_handler(int s) {
  printf("Caught signal %d\n", s);

  if (host_monitor->is_monitoring) {
    h_host_device_interface->lock_thread_id = 0;
    sendDone();

    pthread_join(t_socket_server, NULL);
  }

  checkCuda(cudaFreeHost(h_host_device_interface));
  exit(0);
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
  while (socket_server.getPort() == -1) {}

  // connect SocketClient
  socket_client.connectSocket(socket_server.getPort());

  //CUDA setup
  // runtime must be placed into a state enabling to allocate zero-copy buffers.
  checkCuda(cudaSetDeviceFlags(cudaDeviceMapHost));

  // allocate host_kernelWrapper as pinned memory
  checkCuda(cudaHostAlloc((void**) &h_host_device_interface, sizeof(HostDeviceInterface),
            cudaHostAllocWriteCombined | cudaHostAllocMapped));

  // init HostMonitor
  host_monitor = new HostMonitor(h_host_device_interface);
  host_monitor->start_monitoring();

  // wait for HostMonitor monitoring
  while (!host_monitor->is_monitoring) {}

  printf("host_monitor is_monitoring: %s\n",
         (host_monitor->is_monitoring) ? "true" : "false");

  // test call getValue on Host
  printf("\n\nTEST HostMonitor using getValue host method!\n");
  for (int i = 0; i < 5; i++) {
    int rand_value = rand() % 100; //in the range 0 to 99;
    int value = getValue(i, rand_value);
    printf("Host [%d] getValue(%d): %d\n", i, rand_value, value);
           assert(rand_value+1 == value);
  }

  // sleep before GPU execution
  sleep(2);

  HostDeviceInterface *d_host_device_interface;
  checkCuda(cudaHostGetDevicePointer(&d_host_device_interface, h_host_device_interface, 0));

  // initialize cuPrintf
  cudaPrintfInit();

  device_method<<<1, 8>>>(d_host_device_interface);

  // display the device's output
  printf("\n\nTEST KernelWrapper using getValue device method!\n");
  cudaPrintfDisplay();

  // clean up after cuPrintf
  cudaPrintfEnd();


  printf("\n\nSend DONE and Exit\n");
  // Send DONE to SocketServer
  h_host_device_interface->lock_thread_id = 0;
  sendDone();

  // wait for SocketServer
  pthread_join(t_socket_server, NULL);

  //checkCuda(cudaFreeHost(h_host_device_interface));

  return 0;
}
