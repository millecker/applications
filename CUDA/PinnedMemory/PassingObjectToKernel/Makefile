NVCC = nvcc
CXX=g++

CUDA_INSTALL_PATH=/usr/local/cuda
NVCCFLAGS = -g -O2 -dc -gencode arch=compute_20,code=sm_20 \
 -I$(CUDA_INSTALL_PATH)/include

all: PassingObjectToKernel

PassingObjectToKernel: bin/MyClass.o bin/passing_object_to_kernel.o
		$(NVCC) -I./src -L./bin/ $^ -o $@

bin/passing_object_to_kernel.o: src/passing_object_to_kernel.cu
		$(NVCC) $(NVCCFLAGS) $< -o $@
		
bin/MyClass.o: src/MyClass.cpp src/MyClass.h
		$(NVCC) -c $< -o $@
		
run: PassingObjectToKernel
		./bin/PassingObjectToKernel
		
clean:
		rm -f bin/MyClass.o
		rm -f bin/passing_object_to_kernel.o
		rm -f bin/PassingObjectToKernel
		rm -rf bin/PassingObjectToKernel.dSYM
