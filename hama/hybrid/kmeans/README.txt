###############################################################################
##### Hybrid K-Means Example                                              #####
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
 -Dn=10 -Dk=3 -DvectorDimension=2 -DmaxIterations=10 \
 -DtestExample=false -Ddebug=false]

# Run GPU Testcase to Hama
ant run-gpu [-DnumBspTask=1 -DnumGpuBspTask=1  \
 -DblockSize=1 -DgridSize=1 \
 -Dn=100 -Dk=1 -DvectorDimension=2 -DmaxIterations=10 \
 -DtestExample=true -Ddebug=false]

# Run precompiled KMeans-GPU.jar
hama jar KMeans-GPU.jar 1 1 1 1 10 3 2 10 true true
hama jar KMeans-GPU.jar 1 1 1 1 100 1 2 10 true false
hama jar KMeans-GPU.jar 1 1 384 14 1000000 1 3 10 true false

# Submit CPU Task to Hama
ant run-cpu [-DnumBspTask=2 -DnumGpuBspTask=0  \
 -DblockSize=0 -DgridSize=0 \
 -Dn=10 -Dk=3 -DvectorDimension=2 -DmaxIterations=10 \
 -DtestExample=false -Ddebug=false]

# Run CPU Testcase to Hama
ant run-cpu [-DnumBspTask=2 -DnumGpuBspTask=0  \
 -DblockSize=0 -DgridSize=0 \
 -Dn=100 -Dk=1 -DvectorDimension=2 -DmaxIterations=10 \
 -DtestExample=true -Ddebug=false]

# Run Benchmark
ant run-bench -DbenchTimeLimit='--time-limit 3600s' \
  -DbenchInstrument='--instrument macro' \
  -DbenchMacroMeasurements='-Cinstrument.macro.options.measurements=5' \
  -DbenchMacroWarmup='-Cinstrument.macro.options.warmup=60s'
  [-DbenchTrials='--trials 1']

###############################################################################
