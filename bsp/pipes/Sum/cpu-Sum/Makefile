CC = g++
#CPPFLAGS = -m64 -I$(HAMA_HOME)/../../../../c++/install/include
CPPFLAGS = -m64 -I/home/bafu/workspace/hama-0.5.0-gpu/c++/install/include

cpu-Sum: cpu-Sum.cc
#	$(CC) $(CPPFLAGS) $< -L$(HAMA_HOME)/../../../../c++/install/lib -lhamapipes -lhadooputils -lcrypto -lpthread -g -O2 -o $@
	$(CC) $(CPPFLAGS) $< -L/home/bafu/workspace/hama-0.5.0-gpu/c++/install/lib -lhamapipes -lhadooputils -lcrypto -lpthread -g -O2 -o $@

clean:
	rm -f cpu-Sum
