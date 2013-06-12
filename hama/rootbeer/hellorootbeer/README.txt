###############################################################################
##### HelloRootbeer Example                                               #####
###############################################################################

# Use Apache Ant to build and run example

# Clean all files
ant clean

# Build GPU jar file
ant jar-gpu

# Submit GPU Task to Hama
ant run-gpu

# Submit GPU emulated Task to Hama
ant run-gpu-emu

###############################################################################