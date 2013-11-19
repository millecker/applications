NVCC = nvcc
NVCCFLAGS = -g -O2 -m64 \
 -gencode arch=compute_20,code=sm_20 \
 -gencode arch=compute_35,code=sm_35
#-gencode arch=compute_12,code=sm_12 
CXX=g++
CUDA_INSTALL_PATH=/usr/local/cuda
CFLAGS= -I$(CUDA_INSTALL_PATH)/include
LDFLAGS= -L$(CUDA_INSTALL_PATH)/lib -lcuda -lcudart 

all: SocketCommunication

SocketCommunication: bin/libhadooputils.a bin/libsocket.a src/socket_communication_new.cu
#		$(CXX) $(CFLAGS) $^ $(LDFLAGS) -o bin/$@
#		$(NVCC) $(NVCCFLAGS) -link $^ -o bin/$@
		$(NVCC) $(NVCCFLAGS) $^ -o bin/$@

bin/libhadooputils.a: bin/SerialUtils.o bin/StringUtils.o
		ar rvs $@ $^
		rm -f $^
		
bin/SerialUtils.o: src/hadoop/SerialUtils.cc
		$(CXX) -g -O2 -c $< -o $@
	
bin/StringUtils.o: src/hadoop/StringUtils.cc
		$(CXX) -g -O2 -c $< -o $@	


bin/libsocket.a: bin/SocketServer.o bin/SocketClient.o
		ar rvs $@ $^
		rm -f $^
		
bin/SocketServer.o: src/socket/SocketServer.cc
		$(CXX) -g -O2 -c $< -o $@
	
bin/SocketClient.o: src/socket/SocketClient.cc
		$(CXX) -g -O2 -c $< -o $@


run: SocketCommunication
		./bin/SocketCommunication

debug: SocketCommunication
		sudo ddd --debugger cuda-gdb ./bin/SocketCommunication

clean:
		rm -f bin/libsocket.a
		rm -f bin/libhadooputils.a
		rm -f bin/SocketCommunication
		rm -rf bin/SocketCommunication.dSYM
