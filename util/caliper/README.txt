###############################################################################
##### Caliper R Script                                                    #####
###############################################################################

# Usage
./CaliperResults.R <InputFile> 
   [<MagnitudeNormalizer>] 
   [<XaxisDescription>] [<YaxisDescription>] 
   [<GenerateGeoLinePlot>]

# Examples
./CaliperResults.R \
  results/hadoop/matrixmultiplication/at.illecker.hadoop.rootbeer.examples.matrixmultiplication.MatrixMultiplicationBenchmark.2013-06-23T13\:37\:02Z.json \
  6 "(n=matrixSize)" "(ms)" true

./CaliperResults.R \
  results/hama/piestimator/at.illecker.hama.rootbeer.examples.piestimator.PiEstimatorBenchmark.2013-08-16T16:57:42Z.json \
  9 "(n*1024*14)" "(sec)" true

###############################################################################
