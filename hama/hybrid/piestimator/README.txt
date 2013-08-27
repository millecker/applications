###############################################################################
##### PiEstimatorHybrid Example                                           #####
###############################################################################

# Use Apache Ant to build and run example

# Clean all files
ant clean

# Build jar file
ant jar-gpu

# Submit Hybrid Task to Hama
ant run-gpu [-DnumBspTask=8 -DnumBspGpuTask=1 -Diterations=1433600000]

# Build and run GPU Kernel
ant run-kernel

###############################################################################
