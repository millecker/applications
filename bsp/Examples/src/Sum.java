import java.io.IOException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.DoubleWritable;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.io.Text;
import org.apache.hama.HamaConfiguration;
import org.apache.hama.bsp.BSP;
import org.apache.hama.bsp.BSPJob;
import org.apache.hama.bsp.BSPJobClient;
import org.apache.hama.bsp.BSPPeer;
import org.apache.hama.bsp.ClusterStatus;
import org.apache.hama.bsp.FileOutputFormat;
import org.apache.hama.bsp.TextOutputFormat;
import org.apache.hama.bsp.KeyValueTextInputFormat;
import org.apache.hama.bsp.sync.SyncException;

 
public class Sum extends BSP<Text, Text, Text, DoubleWritable, DoubleWritable> {
    public static final Log LOG = LogFactory.getLog(Sum.class);
    
    private String masterTask;
    private static Path TMP_OUTPUT = new Path("/tmp/sum-" + System.currentTimeMillis());

    @Override
    public void bsp(
        BSPPeer<Text, Text, Text, DoubleWritable, DoubleWritable> peer)
        throws IOException, SyncException, InterruptedException {

      double intermediateSum = 0.0;
      
      Text key = new Text();
      Text value = new Text();
      while (peer.readNext(key, value)) {
    	  LOG.info("DEBUG: key: "+key+" value: "+value);
    	  intermediateSum += Double.parseDouble(value.toString());
      }

      peer.send(masterTask, new DoubleWritable(intermediateSum));
      peer.sync();
    }

    @Override
    public void setup(
        BSPPeer<Text, Text, Text, DoubleWritable, DoubleWritable> peer)
        throws IOException {
      // Choose one as a master
      this.masterTask = peer.getPeerName(peer.getNumPeers() / 2);
    }

    @Override
    public void cleanup(
        BSPPeer<Text, Text, Text, DoubleWritable, DoubleWritable> peer)
        throws IOException {
    	
      if (peer.getPeerName().equals(masterTask)) {
        double sum = 0.0;
        //int numPeers = peer.getNumCurrentMessages();
        DoubleWritable received;
        while ((received = peer.getCurrentMessage()) != null) {
        	sum += received.get();
        }

        peer.write(new Text("Sum"), new DoubleWritable(sum));
      }
    }

  static void printOutput(HamaConfiguration conf) throws IOException {
    FileSystem fs = FileSystem.get(conf);
    FileStatus[] files = fs.listStatus(TMP_OUTPUT);
    for (int i = 0; i < files.length; i++) {
      if (files[i].getLen() > 0) {
        FSDataInputStream in = fs.open(files[i].getPath());
        IOUtils.copyBytes(in, System.out, conf, false);
        in.close();
        break;
      }
    }

    fs.delete(TMP_OUTPUT, true);
  }

  public static void main(String[] args) throws InterruptedException,
      IOException, ClassNotFoundException {
    // BSP job configuration
	  
    HamaConfiguration conf = new HamaConfiguration();

    BSPJob job = new BSPJob(conf);
    // Set the job name
    job.setJobName("Sum Example");
    // set the BSP class which shall be executed
    job.setBspClass(Sum.class);
    // help Hama to locale the jar to be distributed
    job.setJarByClass(Sum.class);
    
    job.setInputPath(new Path("input/examples/test.seq"));
    job.setInputFormat(KeyValueTextInputFormat.class);
    //job.setInputKeyClass(Text.class);
    //job.setInputValueClass(DoubleWritable.class);
    
    job.setOutputKeyClass(Text.class);
    job.setOutputValueClass(DoubleWritable.class);
    
    job.setOutputFormat(TextOutputFormat.class);
    FileOutputFormat.setOutputPath(job, TMP_OUTPUT);
    
    BSPJobClient jobClient = new BSPJobClient(conf);
    ClusterStatus cluster = jobClient.getClusterStatus(true);

    if (args.length > 0) {
    	job.setNumBspTask(Integer.parseInt(args[0]));
    } else {
      // Set to maximum
    	job.setNumBspTask(cluster.getMaxTasks());
    }
    LOG.info("DEBUG: NumBspTask: "+job.getNumBspTask());
    
    long startTime = System.currentTimeMillis();
    if (job.waitForCompletion(true)) {
      printOutput(conf);
      System.out.println("Job Finished in "
          + (System.currentTimeMillis() - startTime) / 1000.0 + " seconds");
    }
  }
  
}