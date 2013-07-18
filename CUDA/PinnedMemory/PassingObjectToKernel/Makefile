NVCC = nvcc
NVCCFLAGS = -g -O2 -m64 \
 -gencode arch=compute_12,code=sm_12 -gencode arch=compute_20,code=sm_20
CXX=g++
CUDA_INSTALL_PATH=/usr/local/cuda
CFLAGS= -I$(CUDA_INSTALL_PATH)/include
LDFLAGS= -L$(CUDA_INSTALL_PATH)/lib -lcuda -lcudart 

all: PassingObjectToKernel

PassingObjectToKernel: bin/MyClass.o bin/passing_object_to_kernel.o
#		$(CXX) $(CFLAGS) $^ $(LDFLAGS) -o $@
		$(NVCC) $(NVCCFLAGS) -link $^ -o $@

bin/passing_object_to_kernel.o: src/passing_object_to_kernel.cu
		$(NVCC) $(NVCCFLAGS) --compile $< -o $@
		
bin/MyClass.o: src/MyClass.cpp
#		$(CXX) -g -O2 $(CFLAGS) $< $(LDFLAGS) -o $@
		$(NVCC) $(NVCCFLAGS) --compile $< -o $@

run: PassingObjectToKernel
		./bin/PassingObjectToKernel

clean:
		rm -f bin/MyClass.o
		rm -f bin/passing_object_to_kernel.o
		rm -f bin/PassingObjectToKernel
		rm -rf bin/PassingObjectToKernel.dSYM
