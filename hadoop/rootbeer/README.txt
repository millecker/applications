###############################################################################
##### Hadoop Rootbeer Examples                                            #####
###############################################################################

# Use Apache Ant to build and run examples in subfolders

# Clean all files
ant clean

# Build GPU jar file
ant jar-gpu

# Build CPU jar file
ant jar-cpu

# Build Benchmark jar file
ant jar-bench

###############################################################################

# Submit GPU Task to Hadoop
ant run-gpu

# Submit GPU native emulated Task to Hadoop
ant run-gpu-nemu

# Submit GPU Java emulated Task to Hadoop
ant run-gpu-jemu

# Submit CPU Task to Hadoop
ant run-cpu

# Run Benchmark
ant run-bench [-DbenchDebug='--debug']

###############################################################################