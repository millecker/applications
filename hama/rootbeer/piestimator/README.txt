###############################################################################
##### PiEstimator Example                                                 #####
###############################################################################

# Use Apache Ant to build and run example

# Clean all files
ant clean

# Build GPU jar file
ant jar-gpu

# Build CPU jar file
ant jar-cpu

# Submit GPU Task to Hama
ant run-gpu [-DnumBspTask=3 -DkernelCount=100 -Diterations=1000]

# Submit GPU native emulated Task to Hama
ant run-gpu-nemu [-DnumBspTask=3 -DkernelCount=1 -Diterations=1000]

# Submit GPU Java emulated Task to Hama
ant run-gpu-jemu [-DnumBspTask=3 -DkernelCount=1 -Diterations=1000]

# Submit CPU Task to Hama
ant run-cpu [-DnumBspTask=3 -DkernelCount=1 -Diterations=1000]

###############################################################################