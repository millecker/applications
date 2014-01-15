###############################################################################
##### TestGlobalGPUSync Example                                           #####
###############################################################################

# Use Apache Ant to build and run example

# Clean all files
ant clean

# Build jar file
ant jar-gpu

# Submit Hybrid Task to Hama
ant run-gpu

# Submit CPU Task to Hama
ant run-cpu [-DnumBspTask=1 -DnumGpuBspTask=0  \
 -DblockSize=0 -DgridSize=0 -Ddebug=false]

 # Submit GPU Task to Hama
ant run-gpu [-DnumBspTask=1 -DnumGpuBspTask=1  \
 -DblockSize=1 -DgridSize=1 -Ddebug=false]

###############################################################################
