###############################################################################
##### Apache Hama Util                                                    #####
###############################################################################

# Use Apache Ant to build and run

# Clean all files
ant clean

# Run jar file
ant run-cpu -DvertexNum=100000 -DedgeNum=1000000 -DpartitionNum=3 -Doutput=input/hama/pagerank/input

###############################################################################