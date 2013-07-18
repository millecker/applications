NVCC = nvcc

CUDA_INSTALL_PATH=/usr/local/cuda
NVCCFLAGS = -g -O2 -gencode arch=compute_12,code=sm_12 -gencode arch=compute_20,code=sm_20 \
 -I$(CUDA_INSTALL_PATH)/include
#CPPFLAGS = -m64 -I$(HADOOP_HOME)/../../c++/Linux-amd64-64/include
#CPPFLAGS = -m64 -I$(HADOOP_HOME)/../../src/c++/install/include

all: HelloCUDA

HelloCUDA: src/HelloCUDA.cu
		$(NVCC) $(NVCCFLAGS) $< -o bin/$@

run: HelloCUDA
		./bin/HelloCUDA

clean:
		rm -f bin/HelloCUDA
		rm -rf bin/HelloCUDA.dSYM
