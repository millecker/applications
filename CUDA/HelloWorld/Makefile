CC = nvcc
#CPPFLAGS = -m64 -I$(HADOOP_HOME)/../../c++/Linux-amd64-64/include
#CPPFLAGS = -m64 -I$(HADOOP_HOME)/../../src/c++/install/include

hello_world: hello_world.cu
	$(CC) $< -g -O2 -o $@

clean:
	rm -f hello_world

