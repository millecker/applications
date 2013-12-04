###############################################################################
##### Hybrid MatrixMultiplication Example                                 #####
###############################################################################

# Use Apache Ant to build and run example

# Clean all files
ant clean

# Build GPU jar file
ant jar-gpu

# Build CPU jar file
ant jar-cpu

# Submit GPU Task to Hama
ant run-gpu [-DnumBspTask=1 -DnumRowsA=1024 -DnumColsA=1024 \
 -DnumRowsB=1024 -DnumColsB=1024 -Ddebug=false]

# Submit GPU native emulated Task to Hama
ant run-gpu-nemu [-DnumBspTask=1 -DnumRowsA=1024 -DnumColsA=1024 \
 -DnumRowsB=1024 -DnumColsB=1024 -Ddebug=false]

# Submit GPU Java emulated Task to Hama
ant run-gpu-jemu [-DnumBspTask=1 -DnumRowsA=1024 -DnumColsA=1024 \
 -DnumRowsB=1024 -DnumColsB=1024 -Ddebug=false]

# Submit CPU Task to Hama
ant run-cpu [-DnumBspTask=8 -DnumRowsA=1024 -DnumColsA=1024 \
 -DnumRowsB=1024 -DnumColsB=1024 -Ddebug=false]
 
 # Build and run GPU Kernel
ant run-kernel
# java -jar MatrixMultiplication-GPU.jar 1024 1024 14 false

# Run Benchmark
ant run-bench -DbenchTimeLimit='--time-limit 600s' \
  -DbenchInstrument='--instrument macro' \
  -DbenchMacroMeasurements='-Cinstrument.macro.options.measurements=5' \
  -DbenchMacroWarmup='-Cinstrument.macro.options.warmup=30s'
  [-DbenchTrials='--trials 1']

###############################################################################
