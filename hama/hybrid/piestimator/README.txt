###############################################################################
##### PiEstimatorHybrid Example                                           #####
###############################################################################

# Use Apache Ant to build and run example

# Clean all files
ant clean

# Build jar file
ant jar-gpu

# Submit Hybrid Task to Hama
ant run-gpu [-DnumBspTask=9 -DnumBspGpuTask=1 -Diterations=1433600000 \
  -DGPUPercentage=12 -DisDebugging=false -DtimeMeasurement=false]

ant run-gpu -DnumBspTask=4 -DnumBspGpuTask=0 -Diterations=7168000000 \
  -DGPUPercentage=0 -DisDebugging=true -DtimeMeasurement=true

ant run-gpu -DnumBspTask=1 -DnumBspGpuTask=1 -Diterations=7168000000 \
  -DGPUPercentage=100 -DisDebugging=false -DtimeMeasurement=false
  
# n = 500000 * 1024 * 14
hama jar PiEstimator-GPU.jar 4 0 7168000000 0 false false
hama jar PiEstimator-GPU.jar 1 1 7168000000 100 false false
# n = 1000000 * 1024 * 14
hama jar PiEstimator-GPU.jar 4 0 14336000000 0 false false
hama jar PiEstimator-GPU.jar 1 1 14336000000 100 false false
# n = 2000000 * 1024 * 14
hama jar PiEstimator-GPU.jar 4 0 28672000000 0 false false
hama jar PiEstimator-GPU.jar 1 1 28672000000 100 false false
# n = 3000000 * 1024 * 14
hama jar PiEstimator-GPU.jar 4 0 43008000000 0 false false
hama jar PiEstimator-GPU.jar 1 1 43008000000 100 false false

# Build and run GPU Kernel
ant run-kernel
# java -jar PiEstimator-GPU.jar 100000 1024 14

# Run Benchmark
ant run-bench -DbenchTimeLimit='--time-limit 10000s' \
  -DbenchInstrument='--instrument macro' \
  -DbenchMacroMeasurements='-Cinstrument.macro.options.measurements=5' \
  -DbenchMacroWarmup='-Cinstrument.macro.options.warmup=30s'
  [-DbenchTrials='--trials 1']

ant run-bench -DbenchTimeLimit='--time-limit 10000s' \
  -DbenchInstrument='--instrument arbitrary' \
  -DbenchMacroMeasurements='-Cinstrument.arbitrary.options.gcBeforeEach=false' \
  -DbenchTrials='--trials 5'

###############################################################################
