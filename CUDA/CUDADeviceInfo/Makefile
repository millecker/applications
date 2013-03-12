CXX=g++

CUDA_INSTALL_PATH=/usr/local/cuda
CFLAGS= -I. -I$(CUDA_INSTALL_PATH)/include -I../Nvidia_OpenCL_SDK_4_2_Linux/shared/inc
LDFLAGS= -L$(CUDA_INSTALL_PATH)/lib ../Nvidia_OpenCL_SDK_4_2_Linux/shared/lib/libshrutil_x86_64.a -lcuda -lcudart 
#CUDAFLAGS=  -lcuda

CUDADeviceInfo: CUDADeviceInfo.cpp
		$(MAKE) -C ../Nvidia_OpenCL_SDK_4_2_Linux/shared
		$(CXX) $(CFLAGS) $< $(LDFLAGS) -g -O2 -o $@
#		nvcc $(CUDAFLAGS) -c $@.o -o $@

clean:
		rm -f CUDADeviceInfo
		$(MAKE) -C ../Nvidia_OpenCL_SDK_4_2_Linux/shared clean
