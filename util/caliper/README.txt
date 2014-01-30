###############################################################################
##### Caliper R Script                                                    #####
###############################################################################

# Usage

./CaliperResults.R <InputFile> 
   [<MagnitudeNormalizer>] 
   [<XaxisDescription>] [<YaxisDescription>] 
   [<GenerateGeoLinePlot>]
   [<CPU_GPU_GenerateGeoLinePlot>]
   [<Speedup_EfficiencyPlot>]

# Examples

./CaliperResults.R \
  results/hadoop/matrixmultiplication/at.illecker.hadoop.rootbeer.examples.matrixmultiplication.MatrixMultiplicationBenchmark.2013-06-23T13\:37\:02Z.json \
  6 "(n=matrixSize)" "(ms)" false true false

./CaliperResults.R \
  results/hama/piestimator/at.illecker.hama.rootbeer.examples.piestimator.PiEstimatorBenchmark.2013-08-19T08:24:43Z.json \
  9 "(n*1024*14)" "(sec)" false true false

./CaliperResults.R \
  results/hama/hybrid/piestimator/at.illecker.hama.hybrid.examples.piestimator.PiEstimatorHybridBenchmark.2013-08-27T08:29:35Z.json \
  9 "(n*1024*14)" "(sec)" true false true

./CaliperResults.R \
  results/hama/hybrid/kmeans/at.illecker.hama.hybrid.examples.kmeans.KMeansHybridBenchmark.2014-01-30T11\:23\:10Z.json \
  9 "(n=1000000)" "(sec)" true true false

###############################################################################
