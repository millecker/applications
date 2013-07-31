###############################################################################
##### HelloHybrid Example                                                 #####
###############################################################################

# Use Apache Ant to build and run example

# Clean all files
ant clean

# Build jar file
ant jar-cpu

# Submit Hybrid Task to Hama
ant run-cpu

###############################################################################