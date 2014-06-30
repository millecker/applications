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

###############################################################################
# Submit GPU Task to Hama
###############################################################################

ant run-gpu [-DnumBspTask=1 -DnumGpuBspTask=1  \
 -DblockSize=256 -DgridSize=14 \
 -DmaxIterations=150 -DmatrixRank=3 -DskipCount=1 \
 -Dalpha=0.01 -DuserCount=0 -DitemCount=0 -DpercentNonZeroValues=0 \
 -DtestExample=true -Ddebug=true]
 [-DinputFile=/home/USERNAME/Downloads/ml-100k/u.data]
 [-Dseparator=::]

# hama jar OnlineCF-GPU.jar 1 1 256 14 150 3 1 0.01 0 0 0 true true
# hama jar OnlineCF-GPU.jar 1 1 256 14 150 3 1 0.01 0 0 0 false false \
   /home/USERNAME/Downloads/ml-100k/u.data
# hama jar OnlineCF-GPU.jar 1 1 256 14 10 3 1 0.01 0 0 0 false false \
   /home/USERNAME/Downloads/ml-1m/ratings.dat ::

# hama jar OnlineCF-GPU.jar 1 1 256 14 150 3 1 0.01 100 100 10 false false

###############################################################################
# Submit CPU Task to Hama
###############################################################################

ant run-cpu [-DnumBspTask=1 -DnumGpuBspTask=0  \
 -DblockSize=0 -DgridSize=0 \
 -DmaxIterations=150 -DmatrixRank=3 -DskipCount=1 \
 -Dalpha=0.01 -DuserCount=0 -DitemCount=0 -DpercentNonZeroValues=0 \
 -DtestExample=true -Ddebug=true]
 [-DinputFile=/home/USERNAME/Downloads/ml-100k/u.data]
 [-Dseparator=::]

# hama jar OnlineCF.jar 1 0 0 0 150 3 1 0.01 0 0 0 true true
# hama jar OnlineCF.jar 1 0 0 0 150 3 1 0.01 0 0 0 false false \
   /home/USERNAME/Downloads/ml-100k/u.data
# hama jar OnlineCF.jar 1 0 0 0 10 3 1 0.01 0 0 0 false false \
   /home/USERNAME/Downloads/ml-1m/ratings.dat ::

# hama jar OnlineCF.jar 1 0 0 0 150 3 1 0.01 100 100 10 false false

###############################################################################
# Run Benchmark
###############################################################################

ant run-bench -DbenchTimeLimit='--time-limit 10000s' \
  -DbenchInstrument='--instrument macro' \
  -DbenchMacroMeasurements='-Cinstrument.macro.options.measurements=5' \
  -DbenchMacroWarmup='-Cinstrument.macro.options.warmup=30s'
  [-DbenchTrials='--trials 1']

###############################################################################
