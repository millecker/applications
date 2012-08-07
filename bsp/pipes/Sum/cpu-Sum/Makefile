CC = g++
CPPFLAGS = -m64 -I$(HAMA_HOME)/../../../../c++/install/include

cpu-Sum: cpu-Sum.cc
	$(CC) $(CPPFLAGS) $< -L$(HAMA_HOME)/../../../../c++/install/lib -lhamapipes -lhadooputils -lcrypto -lpthread -g -O2 -o $@

clean:
	rm -f cpu-Sum
