###############################################################################
##### TestRootbeer Example                                                #####
###############################################################################

# Use Apache Ant to build and run example

# Clean all files
ant clean

# Build jar file
ant jar-gpu

# Submit Hybrid Task to Hama
ant run-gpu

# Build and run GPU Kernel
ant run-kernel

###############################################################################