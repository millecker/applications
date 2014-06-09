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

###############################################################################
# Rootbeer Examples
###############################################################################

# Hadoop MatrixMultiplicationBenchmark
./CaliperResults.R \
  results/hadoop/rootbeer/matrixmultiplication/at.illecker.hadoop.rootbeer.examples.matrixmultiplication.MatrixMultiplicationBenchmark.*.json \
  6 "(n=matrixSize)" "(ms)" false true n 0 false 2
  
# Hama MatrixMultiplicationBenchmark
./CaliperResults.R \
  results/hama/rootbeer/matrixmultiplication/at.illecker.hama.rootbeer.examples.matrixmultiplication.MatrixMultiplicationBenchmark.*.json \
  6 "(n=matrixSize)" "(ms)" false true n 0 false 2

# Hama PiEstimatorBenchmark
./CaliperResults.R \
  results/hama/rootbeer/piestimator/at.illecker.hama.rootbeer.examples.piestimator.PiEstimatorBenchmark.*.json \
  9 "(n*1024*14)" "(sec)" false true n 3 "N (n*1024*1000)" false 2

###############################################################################
# Hybrid Examples
###############################################################################

# Hama PiEstimatorHybridBenchmark
./CaliperResults.R \
  results/hama/hybrid/piestimator/at.illecker.hama.hybrid.examples.piestimator.PiEstimatorHybridBenchmark.*.json \
  9 "(n*1024*14)" "(sec)" true false "" 0 "" true 5

# Hama KMeansHybridBenchmark
./CaliperResults.R \
  results/hama/hybrid/kmeans/at.illecker.hama.hybrid.examples.kmeans.KMeansHybridBenchmark.*.json \
  9 "(n=1000000)" "(sec)" false true k 0 "" false 2

###############################################################################
