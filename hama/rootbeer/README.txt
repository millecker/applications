###############################################################################
##### Hama Rootbeer Examples                                              #####
###############################################################################

# Use Apache Ant to build and run examples in subfolders

# Clean all files
ant clean

# Build GPU jar file
ant jar-gpu

# Build CPU jar file
ant jar-cpu

# Submit GPU Task to Hama
ant run-gpu

# Submit GPU native emulated Task to Hama
ant run-gpu-nemu

# Submit GPU Java emulated Task to Hama
ant run-gpu-jemu

# Submit CPU Task to Hama
ant run-cpu

###############################################################################