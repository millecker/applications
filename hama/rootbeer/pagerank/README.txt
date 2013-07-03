###############################################################################
##### PageRank Example                                                    #####
###############################################################################

# Delete input and output dir
hadoop fs -rmr input/hama/rootbeer/examples/pagerank
hadoop fs -rmr output/hama/rootbeer/examples/pagerank
# Generate random input graph
# HADOOP_HOME must be set to use hama-examples
hama jar ../../../lib/hama-examples-0.6.3-SNAPSHOT.jar gen fastgen 10000 100000 \
 input/hama/rootbeer/examples/pagerank 3

# Use Apache Ant to build and run example

# Clean all files
ant clean

# Build GPU jar file
ant jar-gpu

# Build CPU jar file
ant jar-cpu

# Submit GPU Task to Hama
ant run-gpu -Dinput=input/hama/rootbeer/examples/pagerank \
 -Doutput=output/hama/rootbeer/examples/pagerank 
 [-DbspTasks=3]

# Submit GPU native emulated Task to Hama
ant run-gpu-nemu -Dinput=input/hama/rootbeer/examples/pagerank \
 -Doutput=output/hama/rootbeer/examples/pagerank  
 [-DbspTasks=3]

# Submit GPU Java emulated Task to Hama
ant run-gpu-jemu -Dinput=input/hama/rootbeer/examples/pagerank \
 -Doutput=output/hama/rootbeer/examples/pagerank  
 [-DbspTasks=3]

# Submit CPU Task to Hama
ant run-cpu -Dinput=input/hama/rootbeer/examples/pagerank \
 -Doutput=output/hama/rootbeer/examples/pagerank 
 [-DbspTasks=3]

###############################################################################