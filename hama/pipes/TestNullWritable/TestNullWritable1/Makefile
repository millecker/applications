CC = g++
CPPFLAGS = -m64 -I$(HAMA_HOME)/../../../../c++/src/main/native/utils/api -I$(HAMA_HOME)/../../../../c++/src/main/native/pipes/api

testNullWritable: testNullWritable.cc
	$(CC) $(CPPFLAGS) $< -L$(HAMA_HOME)/../../../../c++/target/native -lhadooputils -lpthread -g -O2 -Wall -o $@

clean:
	rm -f testNullWritable
