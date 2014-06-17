###############################################################################
##### HelloRootbeer Example                                               #####
###############################################################################

# Use Apache Ant to build and run example

# Clean all files
ant clean

# Build GPU jar file
ant jar-gpu

# Submit GPU Task to Hama
ant run-gpu [-DnumBspTask=1 -DkernelCount=100 -Diterations=10000]

# Submit GPU native emulated Task to Hama
ant run-gpu-nemu [-DnumBspTask=1 -DkernelCount=1 -Diterations=10000]

# Submit GPU Java emulated Task to Hama
ant run-gpu-jemu [-DnumBspTask=1 -DkernelCount=1 -Diterations=10000]

# Build and run GPU Kernel
ant run-kernel

###############################################################################