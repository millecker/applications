###############################################################################
##### PiEstimatorHybrid Example                                           #####
###############################################################################

# Use Apache Ant to build and run example

# Clean all files
ant clean

# Build jar file
ant jar-gpu

# Submit Hybrid Task to Hama
ant run-gpu [-DnumBspTask=9 -DnumBspGpuTask=1 -Diterations=1433600000]

# Build and run GPU Kernel
ant run-kernel
# java -jar PiEstimator-GPU.jar 100000 1024 14

# Run Benchmark
ant run-bench -DbenchTimeLimit='--time-limit 1200s' \
  -DbenchInstrument='--instrument macro' \
  -DbenchMacroMeasurements='-Cinstrument.macro.options.measurements=5' \
  -DbenchMacroWarmup='-Cinstrument.macro.options.warmup=30s'
  [-DbenchTrials='--trials 1']
  
###############################################################################
