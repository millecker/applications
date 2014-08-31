###############################################################################
##### Hybrid MatrixMultiplication2 Example                                #####
###############################################################################

# Use Apache Ant to build and run example

# Clean all files
ant clean

# Build GPU jar file
ant jar-gpu

# Build CPU jar file
ant jar-cpu

# Submit GPU Task to Hama
ant run-gpu [-DnumBspTask=1 -DnumGpuBspTask=1 \
 -DnumRowsA=1024 -DnumColsA=1024 -DnumRowsB=1024 -DnumColsB=1024 \
 -DtileWidth=32 -DGPUPercentage=100 -Ddebug=false]

ant run-gpu -DnumBspTask=1 -DnumGpuBspTask=1 \
 -DnumRowsA=4 -DnumColsA=4 -DnumRowsB=4 -DnumColsB=4 \
 -DtileWidth=4 -DGPUPercentage=100 -Ddebug=true

###############################################################################

# Submit CPU Task to Hama
ant run-cpu [-DnumBspTask=8 -DnumGpuBspTask=0 \
 -DnumRowsA=1024 -DnumColsA=1024 -DnumRowsB=1024 -DnumColsB=1024 \
 -DtileWidth=0 -DGPUPercentage=0 -Ddebug=false]

ant run-cpu -DnumBspTask=1 -DnumGpuBspTask=0 \
 -DnumRowsA=4 -DnumColsA=4 -DnumRowsB=4 -DnumColsB=4 \
 -DtileWidth=0 -DGPUPercentage=0 -Ddebug=true
 
###############################################################################

# Run Benchmark
ant run-bench -DbenchTimeLimit='--time-limit 600s' \
  -DbenchInstrument='--instrument macro' \
  -DbenchMacroMeasurements='-Cinstrument.macro.options.measurements=5' \
  -DbenchMacroWarmup='-Cinstrument.macro.options.warmup=30s'
  [-DbenchTrials='--trials 1']

###############################################################################
