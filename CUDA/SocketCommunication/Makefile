NVCC = nvcc
NVCCFLAGS = -g -O2 -m64 \
 -gencode arch=compute_12,code=sm_12 -gencode arch=compute_20,code=sm_20
CXX=g++
CUDA_INSTALL_PATH=/usr/local/cuda
CFLAGS= -I$(CUDA_INSTALL_PATH)/include
LDFLAGS= -L$(CUDA_INSTALL_PATH)/lib -lcuda -lcudart 

all: SocketCommunication

SocketCommunication: bin/libhadooputils.a src/socket_communication.cu
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
		

run: SocketCommunication
		./bin/SocketCommunication

clean:
		rm -f bin/libhadooputils.a
		rm -f bin/SocketCommunication
		rm -rf bin/SocketCommunication.dSYM
