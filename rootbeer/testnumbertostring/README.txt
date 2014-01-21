###############################################################################
##### TestNumberToString Example                                          #####
###############################################################################

# Use Apache Ant to build and run example

# Clean all files
ant clean
 
 # Build and run GPU Kernel
ant run-kernel [-DblockSize=2 -DgridSize=1]

# java -Xmx4G -jar TestNumberToString-GPU.jar 2 1

###############################################################################
