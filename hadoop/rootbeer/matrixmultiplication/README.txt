###############################################################################
##### MatrixMultiplication based on Mahout Example                        #####
###############################################################################

# Use Apache Ant to build and run example

# Clean all files
ant clean

# Build GPU jar file
ant jar-gpu

# Build CPU jar file
ant jar-cpu

# Build Benchmark jar file
ant jar-bench

###############################################################################

# Submit GPU Task to Hadoop
ant run-gpu -DnumRowsA='--numRowsA 256' -DnumColsA='--numColsA 256' \
  -DnumRowsB='--numRowsB 256' -DnumColsB='--numColsB 256' -DtileWidth='--tileWidth 32'
  [-DrbSharedMemSize='-shared-mem-size 16408']  [-DrbMaxRegCount='-maxrregcount 32'] 
  [-Ddebug='--debug true']

ant run-gpu -DnumRowsA='--numRowsA 4' -DnumColsA='--numColsA 4' \
  -DnumRowsB='--numRowsB 4' -DnumColsB='--numColsB 4'  \
  -DtileWidth='--tileWidth 4' -Ddebug='--debug true'

hadoop jar MatrixMultiplication-GPU.jar --numRowsA 1024 --numColsA 1024 \
  --numRowsB 1024 --numColsB 1024 --tileWidth 32 --debug false

hadoop jar MatrixMultiplication-GPU.jar --numRowsA 4096 --numColsA 4096 \
  --numRowsB 4096 --numColsB 4096 --tileWidth 32 --debug false

###############################################################################

# Submit CPU Task to Hadoop
ant run-cpu -DnumRowsA='--numRowsA 256' -DnumColsA='--numColsA 256' \
  -DnumRowsB='--numRowsB 256' -DnumColsB='--numColsB 256'
  [-Ddebug='--debug true']

ant run-cpu -DnumRowsA='--numRowsA 4' -DnumColsA='--numColsA 4' \
  -DnumRowsB='--numRowsB 4' -DnumColsB='--numColsB 4' -Ddebug='--debug true'

hadoop jar MatrixMultiplication.jar --numRowsA 1024 --numColsA 1024 \
  --numRowsB 1024 --numColsB 1024 --debug false

hadoop jar MatrixMultiplication.jar --numRowsA 4096 --numColsA 4096 \
  --numRowsB 4096 --numColsB 4096 --debug false

###############################################################################

# Submit GPU native emulated Task to Hadoop
ant run-gpu-nemu -DnumRowsA='--numRowsA 100' -DnumColsA='--numColsA 100' \
  -DnumRowsB='--numRowsB 100' -DnumColsB='--numColsB 100'
  [-DrbSharedMemSize='-shared-mem-size 16408']  [-DrbMaxRegCount='-maxrregcount 32'] 
  [-Ddebug='--debug true']

# Submit GPU Java emulated Task to Hadoop
ant run-gpu-jemu -DnumRowsA='--numRowsA 100' -DnumColsA='--numColsA 100' \
  -DnumRowsB='--numRowsB 100' -DnumColsB='--numColsB 100'
  [-DrbSharedMemSize='-shared-mem-size 16408']  [-DrbMaxRegCount='-maxrregcount 32']
  [-Ddebug='--debug true']  

###############################################################################

# Run Benchmark 
ant run-bench -DbenchTimeLimit='--time-limit 5000s' \
  -DbenchInstrument='--instrument arbitrary' \
  -DbenchMacroMeasurements='-Cinstrument.arbitrary.options.gcBeforeEach=false' \
  -DbenchTrials='--trials 3'

###############################################################################