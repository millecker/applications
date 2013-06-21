###############################################################################
##### MatrixMultiplication Example                                        #####
###############################################################################

# Use Apache Ant to build and run example

# Clean all files
ant clean

# Build GPU jar file
ant jar-gpu

# Submit GPU Task to Hama
ant run-gpu [-DNumBspTask=3 -DKernelCount=100 -DIterations=10000]

# Submit GPU native emulated Task to Hama
ant run-gpu-nemu [-DNumBspTask=3 -DKernelCount=1 -DIterations=10000]

# Submit GPU Java emulated Task to Hama
ant run-gpu-jemu [-DNumBspTask=3 -DKernelCount=1 -DIterations=10000]

###############################################################################