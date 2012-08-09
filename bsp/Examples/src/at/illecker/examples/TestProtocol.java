package at.illecker.examples;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.filecache.DistributedCache;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.DoubleWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hama.HamaConfiguration;
import org.apache.hama.bsp.BSPJob;
import org.apache.hama.bsp.NullInputFormat;
import org.apache.hama.bsp.NullOutputFormat;
import org.apache.hama.bsp.TextOutputFormat;
import org.apache.hama.pipes.PipesBSP;


public class TestProtocol {

	/**
	 * @param args
	 */
	public static void main(String[] args) {
		// TODO Auto-generated method stub

		HamaConfiguration conf = new HamaConfiguration();
		conf.set("bsp.master.address", "local");
		conf.set("bsp.local.tasks.maximum", "2"); 
		conf.set("fs.default.name", "local"); 
		
	    // hier die executable etc in die conf reinsetzen     
		//conf.set("hama.pipes.executable", "bin/cpu-Sum");
		//conf.set("hama.pipes.executable", "/Users/bafu/workspace/applications/bsp/pipes/Sum/cpu-Sum/cpu-Sum");
	   
	    try {
	    	//DistributedCache.addCacheFile(new URI("/home/bafu/workspace/applications/bsp/pipes/Sum/cpu-Sum/cpu-Sum"), conf);
	    	DistributedCache.addCacheFile(new URI("/home/bafu/workspace/applications/bsp/pipes/TestProtocol/testProtocol"), conf);
		} catch (URISyntaxException e2) {
			// TODO Auto-generated catch block
			e2.printStackTrace();
		}
		
	    BSPJob job = null;
		try {
		
			job = new BSPJob(conf);
		
		} catch (IOException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}
	    
	    job.setBspClass(PipesBSP.class);
	    job.setJarByClass(PipesBSP.class);
	    
	    job.setInputFormat(NullInputFormat.class);
		
	    job.setOutputPath(new Path("/tmp/"));
	    job.setOutputFormat(TextOutputFormat.class);
	    job.setOutputKeyClass(Text.class);
	    job.setOutputValueClass(Text.class);
	    
	    job.setJobName("Test Protocol");
	    
	    try {
			
	    	job.waitForCompletion(true);
			
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (ClassNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	    
	}

}
