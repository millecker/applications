###############################################################################
##### Hama Hybrid (CPU+GPU) Examples                                      #####
###############################################################################
 
 Examples
  1) HelloHybrid
  2) Summation
  3) PiEstimator
  4) MatrixMultiplication
  5) KMeans
  6) OnlineCollaborativeFiltering
 
 Tests
  1) TestRootbeer
  2) TestGlobalGpuSync

###############################################################################

# Use Apache Ant to build and run examples

# Clean all files
ant clean

# Build jar file
ant jar-gpu

# Submit Hybrid Task to Hama
ant run-gpu

# Build and run GPU Kernel
ant run-kernel

###############################################################################
