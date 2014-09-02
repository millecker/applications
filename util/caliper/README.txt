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
      [<OtherLegendDescription>]
      [<XaxisCPUGPUTextAngle>]
   ]
   [<Speedup_EfficiencyPlot=true|false>]
   [YticksStart]
   [YticksIncrement]
   [XticksStart]
   [XticksIncrement]
   [barText]
   [barTextPosition]
   [barTextSize]
   [XticksText]
   [XAxisBarTextAngle]
   [EfficiencyValue]

###############################################################################
# Rootbeer Examples
###############################################################################

######################################
# MatrixMultiplicationBenchmark Hadoop
######################################

./CaliperResults.R \
  results/hadoop/matrixmultiplication/at.illecker.hadoop.rootbeer.examples.matrixmultiplication.MatrixMultiplicationBenchmark.2014*.json \
  3 "(n=matrixSize)" "(sec)" false true n 0 "Matrix Size" "1 CPU map task,1 GPU map task" "0,0" false 10 200 256 256

######################
# PiEstimatorBenchmark
######################

./CaliperResults.R \
  results/hama/rootbeer/piestimator/at.illecker.hama.rootbeer.examples.piestimator.PiEstimatorBenchmark.*.json \
  9 "(n*1024*14)" "(sec)" false true n 3 "N" "" "90,90" false 2

###############################################################################
# Hybrid Examples
###############################################################################

############################
# PiEstimatorHybridBenchmark
############################

# 8 CPU tasks and full GPU power
./CaliperResults.R \
  results/hama/hybrid/piestimator/at.illecker.hama.hybrid.examples.piestimator.PiEstimatorHybridBenchmark.*.CPU.vs.GPU.json \
  9 "8 CPU tasks and 1 full GPU task '(italic(n)%.%1024%.%14)'" "(sec)" false true \
  n 3 "Number of Iterations '(italic(N)%.%1000%.%1024%.%14)'" "8 CPU tasks,1 GPU task" "90,0" false 5 5 20 40

# 1 sequential CPU thread and 1 sequential GPU block with one thread
./CaliperResults.R \
  results/hama/hybrid/piestimator/at.illecker.hama.hybrid.examples.piestimator.PiEstimatorHybridBenchmark.*.GPU.vs.GPU.sequential.json \
  9 "Sequential tasks '(italic(n)%.%1024%.%14)'" "(sec)" false true \
  n 3 "Number of Iterations '(italic(N)%.%1000%.%1024%.%14)'" "one sequential CPU task,one sequential GPU task" "0,0" false 10 50 2 2

# 1 sequential CPU thread and 1 full GPU core
./CaliperResults.R \
  results/hama/hybrid/piestimator/at.illecker.hama.hybrid.examples.piestimator.PiEstimatorHybridBenchmark.*.CPU.vs.GPU.oneGPUcore.json \
  9 "1 sequential CPU task and 1 GPU core '(italic(n)%.%1024%.%14)'" "(sec)" false true \
  n 3 "Number of Iterations '(italic(N)%.%1000%.%1024%.%14)'" "one sequential CPU task,one GPU core" "90,0" false 10 20 20 40

./CaliperResults.R \
  results/hama/hybrid/piestimator/at.illecker.hama.hybrid.examples.piestimator.PiEstimatorHybridBenchmark.*.GPUPercentage.json \
  9 "Percentage of GPU workload" "(sec)" true false "" 0 "" "" "" false 0 5 0 0\
  "12% on 1 GPU and 88% on 8 CPU tasks,50% on 1 GPU and 50% on 8 CPU tasks,60% on 1 GPU and 40% on 8 CPU tasks,\
70% on 1 GPU and 30% on 8 CPU tasks,80% on 1 GPU and 20% on 8 CPU tasks,90% on 1 GPU and 10% on 8 CPU tasks,\
99% on 1 GPU and 1% on 8 CPU tasks" 1.5 8 "gray,gray,gray,gray,#F39200,gray,gray" "12%,50%,60%,70%,80%,90%,99%" 0

./CaliperResults.R \
  results/hama/hybrid/piestimator/at.illecker.hama.hybrid.examples.piestimator.PiEstimatorHybridBenchmark.*.GPUPercentage.2.json \
  9 "Percentage of GPU workload" "(sec)" true false "" 0 "" "" "" false 0 5 0 0\
  "12% on 1 GPU and 88% on 8 CPU tasks,50% on 1 GPU and 50% on 8 CPU tasks,60% on 1 GPU and 40% on 8 CPU tasks,\
70% on 1 GPU and 30% on 8 CPU tasks,80% on 1 GPU and 20% on 8 CPU tasks,85% on 1 GPU and 15% on 8 CPU tasks,\
90% on 1 GPU and 10% on 8 CPU tasks,99% on 1 GPU and 1% on 8 CPU tasks" \
  1.5 8 "gray,gray,gray,gray,#F39200,gray,gray,gray" "12%,50%,60%,70%,80%,85%,90%,99%" 0

./CaliperResults.R \
  results/hama/hybrid/piestimator/at.illecker.hama.hybrid.examples.piestimator.PiEstimatorHybridBenchmark.*.GPUPercentage80.json \
  9 "Number of Tasks" "(sec)" true false "" 0 "" "" "" true 0 10 0 0 \
  "1 Task on CPU,2 Tasks on CPU,3 Tasks on CPU,4 Tasks on CPU,5 Tasks on CPU,6 Tasks on CPU,7 Tasks on CPU,8 Tasks on CPU,8 CPUs|1 GPU" 4 7 \
  "gray,gray,gray,gray,gray,gray,gray,gray,#F39200" "1,2,3,4,5,6,7,8,9" 0

./CaliperResults.R \
  results/hama/hybrid/piestimator/at.illecker.hama.hybrid.examples.piestimator.PiEstimatorHybridBenchmark.*.GPUPercentage80.2.json \
  9 "Number of Tasks" "(sec)" true false "" 0 "" "" "" true 0 10 0 0 \
  "1 Task on CPU,2 Tasks on CPU,3 Tasks on CPU,4 Tasks on CPU,5 Tasks on CPU,6 Tasks on CPU,7 Tasks on CPU,8 Tasks on CPU,8 CPUs|1 GPU" 4 7 \
  "gray,gray,gray,gray,gray,gray,gray,gray,#F39200" "1,2,3,4,5,6,7,8,9" 0 0.4987

############################
# MatrixMultiplicationHybridBenchmark
############################

# 4 CPU tasks and 1 GPU task
./CaliperResults.R \
  results/hama/hybrid/matrixmultiplication/at.illecker.hama.hybrid.examples.matrixmultiplication2.MatrixMultiplicationHybridBenchmark.*.CPU.vs.GPU.json \
  9 "Matrix Size" "(sec)" false true n 0 "Matrix Size" "4 CPU tasks,1 GPU task" "0,0" false 5 50 256 256


# GPUPercentage
# Add to line 481
#    geom_text(aes(y=barTextPosition,label=barText),family=fontType,size=7,angle=90,hjust=0) +
#    annotate(geom="text",x=6,y=6,label="70% on 1 GPU task\n30% on 4 CPU tasks",family=fontType,size=7,angle=90,hjust=0) +
#    annotate(geom="text",x=7,y=6,label="80% on 1 GPU task\n20% on 4 CPU tasks",family=fontType,size=7,angle=90,hjust=0) +
#    annotate(geom="text",x=8,y=6,label="90% GPU\n10% CPU",family=fontType,size=7,angle=90,hjust=0) +
#    annotate(geom="text",x=9,y=4,label="95% GPU\n5% CPU",family=fontType,size=5,angle=90,hjust=0) +

./CaliperResults.R \
  results/hama/hybrid/matrixmultiplication/at.illecker.hama.hybrid.examples.matrixmultiplication2.MatrixMultiplicationHybridBenchmark.*.GPUPercentage.json \
  9 "Percentage of GPU workload" "(sec)" true false "" 0 "" "" "" false 0 50 0 0 \
  "20% on 1 GPU task and 80% on 4 CPU tasks,30% on 1 GPU task and 70% on 4 CPU tasks,40% on 1 GPU task and 60% on 4 CPU tasks,\
50% on 1 GPU task and 50% on 4 CPU tasks,60% on 1 GPU task and 40% on 4 CPU tasks, , , , " \
  6 7 "gray,gray,gray,gray,gray,gray,gray,gray,#F39200" "20%,30%,40%,50%,60%,70%,80%,90%,95%" 0


# GPUPercentage 95 %
# Change lines 820
#    scale_y_continuous(breaks = round(seq(minY, maxY, by = 5), 1)) +

./CaliperResults.R \
  results/hama/hybrid/matrixmultiplication/at.illecker.hama.hybrid.examples.matrixmultiplication2.MatrixMultiplicationHybridBenchmark.*.GPUPercentage95.json \
  9 "Number of Tasks" "(sec)" true false "" 0 "" "" "" true 0 100 0 0 \
  "1 Task on CPU,2 Tasks on CPU,3 Tasks on CPU,4 Tasks on CPU,4 Tasks on CPU|1 Task on GPU" 45 10 \
  "gray,gray,gray,gray,#F39200" "1,2,3,4,5" 0 0.331

#######################
# KMeansHybridBenchmark
#######################
./CaliperResults.R \
  results/hama/hybrid/kmeans/at.illecker.hama.hybrid.examples.kmeans.KMeansHybridBenchmark.*.CPU.vs.GPU.k.json \
  9 "(n=2000000)" "(sec)" false true k 0 "Number of Centroids 'bolditalic(k)'" \
  "2 CPU tasks,1 GPU task" "90,0" false 30 20 50 50

./CaliperResults.R \
  results/hama/hybrid/kmeans/at.illecker.hama.hybrid.examples.kmeans.KMeansHybridBenchmark.*.CPU.vs.GPU.n.json \
  9 "(k=500)" "(sec)" false true n 0 "Number of Input Vectors 'bolditalic(n)'" \
  "2 CPU tasks,1 GPU task" "90,0" false 20 20 250000 250000

./CaliperResults.R \
  results/hama/hybrid/kmeans/at.illecker.hama.hybrid.examples.kmeans.KMeansHybridBenchmark.*.GPUPercentage.json \
  9 "Percentage of GPU workload" "(sec)" true false "" 0 "" "" "" false 0 5 0 0\
  "20% on 1 GPU and 80% on 4 CPU tasks,30% on 1 GPU and 70% on 4 CPU tasks,40% on 1 GPU and 60% on 4 CPU tasks,\
50% on 1 GPU and 50% on 4 CPU tasks,60% on 1 GPU and 40% on 4 CPU tasks,70% on 1 GPU and 30% on 4 CPU tasks,\
75% on 1 GPU and 25% on 4 CPU tasks,80% on 1 GPU and 20% on 4 CPU tasks,90% on 1 GPU and 10% on 4 CPU tasks" 1.5 8 \
"gray,gray,gray,gray,gray,gray,#F39200,gray,gray" "20%,30%,40%,50%,60%,70%,75%,80%,90%" 0

./CaliperResults.R \
  results/hama/hybrid/kmeans/at.illecker.hama.hybrid.examples.kmeans.KMeansHybridBenchmark.*.GPUPercentage75.json \
  9 "Number of Tasks" "(sec)" true false "" 0 "" "" "" true 0 10 0 0 \
  "1 Task on CPU,2 Tasks on CPU,3 Tasks on CPU,4 Tasks on CPU,4 CPUs|1 GPU" 5 10 \
  "gray,gray,gray,gray,#F39200" "1,2,3,4,5" 0 0.331

./CaliperResults.R \
  results/hama/hybrid/kmeans/at.illecker.hama.hybrid.examples.kmeans.KMeansHybridBenchmark.*Z.json \
  9 "Number of Tasks" "(sec)" true false "" 0 "" "" "" true 0 10 0 0 \
  "1 Task on CPU,2 Tasks on CPU,3 Tasks on CPU,4 Tasks on CPU,4 Tasks on CPU|1 Task on GPU" 5 9 \
  "gray,gray,gray,gray,#F39200" "1,2,3,4,5" 0

./CaliperResults.R \
  results/hama/hybrid/kmeans/at.illecker.hama.hybrid.examples.kmeans.KMeansHybridBenchmark.*.oneGPUcore.json \
  9 "One full GPU core" "(sec)" true false "" 0 "" "" "" false 0 10 0 0 \
  "1 GPU core" 5 10 "gray" "1" 0

#########################
# OnlineCFHybridBenchmark
#########################

./CaliperResults.R \
  results/hama/hybrid/onlinecf/at.illecker.hama.hybrid.examples.onlinecf.OnlineCFHybridBenchmark.*.CPU.vs.GPU.iteration.json \
  9 "(n=m=5000,k=256)" "(sec)" false true iteration 0 "Number of Iterations 'bolditalic(i)'" \
  "1 CPU task,1 GPU task" "0,0" false 50 100 0 25

./CaliperResults.R \
  results/hama/hybrid/onlinecf/at.illecker.hama.hybrid.examples.onlinecf.OnlineCFHybridBenchmark.*.CPU.vs.GPU.percentNonZeroValues.json \
  9 "(n=m=5000,k=256)" "(sec)" false true percentNonZeroValues 0 "Percentage of non-zero Ratings" \
  "1 CPU task,1 GPU task" "0,0" false 50 100 1 1

./CaliperResults.R \
  results/hama/hybrid/onlinecf/at.illecker.hama.hybrid.examples.onlinecf.OnlineCFHybridBenchmark.*Z.json \
  9 "Number of Tasks" "(sec)" true false "" 0 "" "" "" true 0 10 0 0 \
  "1 Task on CPU,2 Tasks on CPU,3 Tasks on CPU,4 Tasks on CPU,4 Tasks on CPU and 1 Task on GPU" 2 12 \
  "gray,gray,gray,gray,#F39200" "1,2,3,4,5" 0

###############################################################################
