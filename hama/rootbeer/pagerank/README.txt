###############################################################################
##### PageRank Example                                                    #####
###############################################################################

# Prepare input
# HADOOP_HOME must be set
hama jar hama-examples-0.6.3-SNAPSHOT.jar gen fastgen 100000 1000000 input/hama/pagerank/graph 3

# Use Apache Ant to build and run example

# Clean all files
ant clean

# Build GPU jar file
ant jar-gpu

# Build CPU jar file
ant jar-cpu

# Submit GPU Task to Hama
ant run-gpu -Dinput=input/hama/pagerank/graph -Doutput=output/hama/pagerank/graph [-DbspTasks=3]

# Submit GPU native emulated Task to Hama
ant run-gpu-nemu -Dinput=input/hama/pagerank/graph -Doutput=output/hama/pagerank/graph [-DbspTasks=3]

# Submit GPU Java emulated Task to Hama
ant run-gpu-jemu -Dinput=input/hama/pagerank/graph -Doutput=output/hama/pagerank/graph [-DbspTasks=3]

# Submit CPU Task to Hama
ant run-cpu -Dinput=input/hama/pagerank/input.seq -Doutput=output/hama/pagerank [-DbspTasks=3]

###############################################################################