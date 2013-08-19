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
ant run-gpu [-DnumBspTask=1 -Diterations=1433600000]

# Submit GPU native emulated Task to Hama
ant run-gpu-nemu [-DnumBspTask=1 -Diterations=1433600000]

# Submit GPU Java emulated Task to Hama
ant run-gpu-jemu [-DnumBspTask=1 -Diterations=1433600000]

# Submit CPU Task to Hama
ant run-cpu [-DnumBspTask=8 -Diterations=1433600000]

# Build and run GPU Kernel
ant run-kernel
# java -jar PiEstimator-GPU.jar 100000 1024 14

# Run Benchmark
ant run-bench -DbenchTimeLimit='--time-limit 600s' \
  -DbenchInstrument='--instrument macro' \
  -DbenchMacroMeasurements='-Cinstrument.macro.options.measurements=5' \
  -DbenchMacroWarmup='-Cinstrument.macro.options.warmup=30s'
  [-DbenchTrials='--trials 1']

###############################################################################
