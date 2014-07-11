###############################################################################
##### Caliper R Script                                                    #####
###############################################################################

###############################################################################
# Usage and Command Line Arguments
###############################################################################

./CaliperResults.R <JsonInputFile> 
   [<MagnitudeNormalizer=PowerOf10>] 
   [<XaxisDescription>]
   [<YaxisDescription>] 
   [<GenerateGeoLinePlot=true|false>]
   [<GenerateGeoLinePlot_CPU_GPU=true|false>
      <Variable=ParameterOnX>
      <VariableNormalizer=PowerOf10>
      [<OtherXaxisDescription>]
   ]
   [<Speedup_EfficiencyPlot=true|false>]
   [ticksIncrement]
   [ticksStart]
   [barText]
   [barTextPosition]
   [barTextSize]

###############################################################################
# Rootbeer Examples
###############################################################################

###############################
# MatrixMultiplicationBenchmark
###############################
./CaliperResults.R \
  results/hama/rootbeer/matrixmultiplication/at.illecker.hama.rootbeer.examples.matrixmultiplication.MatrixMultiplicationBenchmark.*.json \
  6 "(n=matrixSize)" "(ms)" false true n 0 "" false 2

######################
# PiEstimatorBenchmark
######################
./CaliperResults.R \
  results/hama/rootbeer/piestimator/at.illecker.hama.rootbeer.examples.piestimator.PiEstimatorBenchmark.*.json \
  9 "(n*1024*14)" "(sec)" false true n 3 "N" false 2

###############################################################################
# Hybrid Examples
###############################################################################

############################
# PiEstimatorHybridBenchmark
############################
./CaliperResults.R \
  results/hama/hybrid/piestimator/at.illecker.hama.hybrid.examples.piestimator.PiEstimatorHybridBenchmark.*.json \
  9 "(n*1024*14)" "(sec)" true false "" 0 "" true 10 0 \
  "1 Task on CPU,2 Tasks on CPU,3 Tasks on CPU,4 Tasks on CPU,5 Tasks on CPU,6 Tasks on CPU,7 Tasks on CPU,8 Tasks on CPU,8 Tasks on CPU|1 Task on GPU" 4 6

#######################
# KMeansHybridBenchmark
#######################
./CaliperResults.R \
  results/hama/hybrid/kmeans/at.illecker.hama.hybrid.examples.kmeans.KMeansHybridBenchmark.*.json \
  9 "(n=2000000)" "(sec)" false true k 0 "k" false 5 30

./CaliperResults.R \
  results/hama/hybrid/kmeans/at.illecker.hama.hybrid.examples.kmeans.KMeansHybridBenchmark.*.json \
  9 "(k=500)" "(sec)" false true n 0 "n" false 5 20

./CaliperResults.R \
  results/hama/hybrid/kmeans/at.illecker.hama.hybrid.examples.kmeans.KMeansHybridBenchmark.*.json \
  9 "(n=1000000, k=500)" "(sec)" true false "" 0 "" true 10 0 \
  "1 Task on CPU,2 Tasks on CPU,3 Tasks on CPU,4 Tasks on CPU,4 Tasks on CPU|1 Task on GPU" 5 8

#########################
# OnlineCFHybridBenchmark
#########################

./CaliperResults.R \
  results/hama/hybrid/onlinecf/at.illecker.hama.hybrid.examples.onlinecf.OnlineCFHybridBenchmark.*.json \
  9 "(n=m=5000,k=256)" "(sec)" false true iteration 0 "iterations" false 50 50

./CaliperResults.R \
  results/hama/hybrid/onlinecf/at.illecker.hama.hybrid.examples.onlinecf.OnlineCFHybridBenchmark.*.json \
  9 "(n=m=5000,k=256)" "(sec)" false true percentNonZeroValues 0 "% of non-zero ratings" false 50 50

./CaliperResults.R \
  results/hama/hybrid/onlinecf/at.illecker.hama.hybrid.examples.onlinecf.OnlineCFHybridBenchmark.*.json \
  9 "(1M MovieLens dataset, k=3, 1 iteration)" "(sec)"  true false "" 0 "" true 10 0 \
  "1 Task on CPU,2 Tasks on CPU,3 Tasks on CPU,4 Tasks on CPU,4 Tasks on CPU and 1 Task on GPU" 2 8

###############################################################################
