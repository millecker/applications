CXX=g++

CUDA_INSTALL_PATH=/usr/local/cuda
CFLAGS= -I. -I$(CUDA_INSTALL_PATH)/include -I/Developer/GPU\ Computing/shared/inc -I/Developer/GPU\ Computing/C/common/inc
LDFLAGS= -L$(CUDA_INSTALL_PATH)/lib /Developer/GPU\ Computing/shared/lib/libshrutil_x86_64.a -lcuda -lcudart 
#CUDAFLAGS=  -lcuda

CUDADeviceInfo: CUDADeviceInfo.cpp
		$(CXX) $(CFLAGS) $< $(LDFLAGS) -g -O2 -o $@
#		nvcc $(CUDAFLAGS) -c $@.o -o $@

clean:
		rm -f CUDADeviceInfo