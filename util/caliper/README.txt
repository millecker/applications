###############################################################################
##### Caliper R Script                                                    #####
###############################################################################

# Usage

./CaliperResults.R <InputFile> 
   [<MagnitudeNormalizer>] 
   [<XaxisDescription>] [<YaxisDescription>] 
   [<GenerateGeoLinePlot>]
   [<CPU_GPU_GenerateGeoLinePlot>]

# Examples

./CaliperResults.R \
  results/hadoop/matrixmultiplication/at.illecker.hadoop.rootbeer.examples.matrixmultiplication.MatrixMultiplicationBenchmark.2013-06-23T13\:37\:02Z.json \
  6 "(n=matrixSize)" "(ms)" false true

./CaliperResults.R \
  results/hama/piestimator/at.illecker.hama.rootbeer.examples.piestimator.PiEstimatorBenchmark.2013-08-16T16:57:42Z.json \
  9 "(n*1024*14)" "(sec)" false true

./CaliperResults.R \
  results/hama/piestimator_hybrid/at.illecker.hama.hybrid.examples.piestimator.PiEstimatorHybridBenchmark.2013-08-27T08:29:35Z.json \
  9 "(n*1024*14)" "(sec)" true false

###############################################################################
