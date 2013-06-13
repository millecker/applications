###############################################################################
##### HelloRootbeer Example                                               #####
###############################################################################

# Use Apache Ant to build and run example

# Clean all files
ant clean

# Build GPU jar file
ant jar-gpu

# Submit GPU Task to Hama
ant run-gpu [-DNumBspTask=3 -DKernelCount=100 -DIterations=1000]

# Submit GPU emulated Task to Hama
ant run-gpu-emu [-DNumBspTask=3 -DKernelCount=100 -DIterations=1000]

###############################################################################