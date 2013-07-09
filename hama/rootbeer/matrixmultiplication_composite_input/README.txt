###############################################################################
##### MatrixMultiplication using CompositeInputFormat Example             #####
###############################################################################

# Use Apache Ant to build and run example

# Clean all files
ant clean

# Build GPU jar file
ant jar-gpu

# Build CPU jar file
ant jar-cpu

# Submit GPU Task to Hama
ant run-gpu [-DnumBspTask=3 -DnumRowsA=100 -DnumColsA=100 \
 -DnumRowsB=100 -DnumColsB=100 -Ddebug=false]

# Submit GPU native emulated Task to Hama
ant run-gpu-nemu [-DnumBspTask=3 -DnumRowsA=100 -DnumColsA=100 \
 -DnumRowsB=100 -DnumColsB=100 -Ddebug=false]

# Submit GPU Java emulated Task to Hama
ant run-gpu-jemu [-DnumBspTask=3 -DnumRowsA=100 -DnumColsA=100 \
 -DnumRowsB=100 -DnumColsB=100 -Ddebug=false]

# Submit CPU Task to Hama
ant run-cpu [-DnumBspTask=3 -DnumRowsA=100 -DnumColsA=100 \
 -DnumRowsB=100 -DnumColsB=100 -Ddebug=false]

###############################################################################