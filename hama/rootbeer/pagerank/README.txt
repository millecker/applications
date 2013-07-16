###############################################################################
##### PageRank Example                                                    #####
###############################################################################

# Delete input and output dir
hadoop fs -rmr input/hama/rootbeer/examples/pagerank
hadoop fs -rmr output/hama/rootbeer/examples/pagerank

# Generate random input graph
# HADOOP_HOME must be set to use hama-examples
hama jar ../../../lib/hama-examples-0.6.3-SNAPSHOT.jar gen fastgen 1000 1000 \
 input/hama/rootbeer/examples/pagerank 1

# Check inputSplits defined by blocks
# hadoop fsck /user/bafu/input/hama/rootbeer/examples/pagerank/part-00000 -blocks
# e.g., Total blocks (validated): 8 require 8 bspTasks

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