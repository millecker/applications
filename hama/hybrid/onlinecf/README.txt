###############################################################################
##### Online Collaborative Filtering Example                              #####
###############################################################################

# Use Apache Ant to build and run example

# Clean all files
ant clean

# Build GPU jar file
ant jar-gpu

# Build CPU jar file
ant jar-cpu

# Submit GPU Task to Hama
ant run-gpu [-DnumBspTask=1 -DnumGpuBspTask=1  \
 -DblockSize=1 -DgridSize=1 \
 -DmaxIterations=150 -DmatrixRank=3 -DskipCount=1 \
 -DtestExample=false -Ddebug=false]

# Submit CPU Task to Hama
ant run-cpu [-DnumBspTask=2 -DnumGpuBspTask=0  \
 -DblockSize=0 -DgridSize=0 \
 -DmaxIterations=150 -DmatrixRank=3 -DskipCount=1 \
 -DtestExample=true -Ddebug=true]

# Run Benchmark
ant run-bench -DbenchTimeLimit='--time-limit 600s' \
  -DbenchInstrument='--instrument macro' \
  -DbenchMacroMeasurements='-Cinstrument.macro.options.measurements=5' \
  -DbenchMacroWarmup='-Cinstrument.macro.options.warmup=30s'
  [-DbenchTrials='--trials 1']

###############################################################################
