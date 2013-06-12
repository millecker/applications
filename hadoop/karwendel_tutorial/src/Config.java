import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class Config {
	
	public static String hadoopRoot = "/hadoop-1.0.0";
	public static String hadoopBin = "/bin/hadoop";
	public static String hadoopDaemon = "/sbin/hadoop-daemon.sh";
	public static String hadoopCoreSite = "/core-site.xml";
	public static String hadoopHdfsSite = "/hdfs-site.xml";
	public static String hadoopMapredSite = "/mapred-site.xml";
	
	/* MASTER */
	public static String hadoopMasterConfig = "/etc/hadoop";

	/* MASTER */
	public static String hadoopSlaveConfig = "/etc/hadoop_slave";
	
	
	public String workingDir;
	public String username;
	public String hostname;
	public int hadoopMasterHdfsPort = 50001;
	public int hadoopMasterJobTrackerPort = 50002;
	
	
	public Config(String workingdir) {
		this(workingdir,0,0);
	}
		
	public Config(String workingdir, int hdfsPort, int jobTrackerPort) {
		workingDir = workingdir;
		if (hdfsPort!=0)
			hadoopMasterHdfsPort = hdfsPort;
		if (jobTrackerPort!=0)
			hadoopMasterJobTrackerPort = jobTrackerPort;
		
		username = execProcess("whoami",false);
		username = username.trim();
		
		hostname = execProcess("hostname --fqdn",false);
		hostname = hostname.trim();
		
		if (!checkFiles()) {
			System.out.println("HadoopSystem exited - Missing files!");
			System.exit(1);
		}
		
		updateConfigFiles();
		
		System.out.println("HadoopSystem config complete.");
		System.out.println("User: "+username);
		System.out.println("HDFS: hdfs//"+hostname+":"+hadoopMasterHdfsPort);
		System.out.println("JobTracker: "+hostname+":"+hadoopMasterJobTrackerPort);
		System.out.println("TempDir: "+workingDir+hadoopRoot+"/tmp");
	}
	
	public boolean checkFiles() {
		
		return checkDirectory(workingDir+hadoopRoot) &&
				checkFile(workingDir+hadoopRoot+hadoopMasterConfig+hadoopCoreSite) &&
				checkFile(workingDir+hadoopRoot+hadoopMasterConfig+hadoopHdfsSite) &&
				checkFile(workingDir+hadoopRoot+hadoopMasterConfig+hadoopMapredSite) &&				
				checkFile(workingDir+hadoopRoot+hadoopSlaveConfig+hadoopCoreSite) &&
				checkFile(workingDir+hadoopRoot+hadoopSlaveConfig+hadoopHdfsSite) &&
				checkFile(workingDir+hadoopRoot+hadoopSlaveConfig+hadoopMapredSite)					;
	}
	
	private boolean checkFile(String file) {
		boolean checkResult = new File(file).isFile();
		if (!checkResult)
			System.out.println(file+" is missing!");
		return checkResult;
	}
	
	private boolean checkDirectory(String dir) {
		boolean checkResult = new File(dir).isDirectory();
		if (!checkResult)
			System.out.println(dir+" is missing!");
		return checkResult;
	}
	
	public void updateConfigFiles() {
		
		String bindHostname = "192.168.71.100";
		
		//update core-site.xml in master		
		updateFile(workingDir+hadoopRoot+hadoopMasterConfig+hadoopCoreSite,
				"<name>fs.default.name</name>\r\n"+
				"    <value>.*?</value>",
				"<name>fs.default.name</name>\r\n"+
				"    <value>hdfs://"+bindHostname+":"+hadoopMasterHdfsPort+"</value>");
		
		updateFile(workingDir+hadoopRoot+hadoopMasterConfig+hadoopCoreSite,
				"<name>hadoop.tmp.dir</name>\r\n"+
				"    <value>.*?</value>",
				"<name>hadoop.tmp.dir</name>\r\n"+
				"    <value>"+workingDir+hadoopRoot+"/tmp"+"</value>");
		
		//update core-site.xml in slave	
		updateFile(workingDir+hadoopRoot+hadoopSlaveConfig+hadoopCoreSite,
				"<name>fs.default.name</name>\r\n"+
				"    <value>.*?</value>",
				"<name>fs.default.name</name>\r\n"+
				"    <value>hdfs://"+bindHostname+":"+hadoopMasterHdfsPort+"</value>");
			
		updateFile(workingDir+hadoopRoot+hadoopSlaveConfig+hadoopCoreSite,
				"<name>hadoop.tmp.dir</name>\r\n"+
				"    <value>.*?</value>",
				"<name>hadoop.tmp.dir</name>\r\n"+
				"    <value>"+workingDir+hadoopRoot+"/tmp"+"</value>");
		
		
		//update mapred-site.xml in master	    
	    updateFile(workingDir+hadoopRoot+hadoopMasterConfig+hadoopMapredSite,
				"<name>mapred.job.tracker</name>\r\n"+
				"    <value>.*?</value>",
				"<name>mapred.job.tracker</name>\r\n"+
				"    <value>"+bindHostname+":"+hadoopMasterJobTrackerPort+"</value>");

		//update mapred-site.xml in slave
	    updateFile(workingDir+hadoopRoot+hadoopSlaveConfig+hadoopMapredSite,
				"<name>mapred.job.tracker</name>\r\n"+
				"    <value>.*?</value>",
				"<name>mapred.job.tracker</name>\r\n"+
				"    <value>"+bindHostname+":"+hadoopMasterJobTrackerPort+"</value>");

	}
	
	private void updateFile(String filepath, String search, String replace) {
	
		try {
           File file = new File(filepath);
           BufferedReader reader = new BufferedReader(new FileReader(file));
           
           String line = "", oldtext = "";
           while((line = reader.readLine()) != null)
           {
               oldtext += line + "\r\n";
           }
           reader.close();
           
           // replace 
           Pattern pattern = Pattern.compile(search,Pattern.MULTILINE |
        		   Pattern.DOTALL);
           Matcher m = pattern.matcher(oldtext);
           String newtext = m.replaceFirst(replace);
           //System.out.println(newtext);
          
           FileWriter writer = new FileWriter(filepath,false);
           writer.write(newtext);
           writer.close();
       }
       catch (IOException ioe){
           ioe.printStackTrace();
       }
	}
	
	public static String execProcess(String path, boolean output){
		//System.out.println("HadoopSystem - execProcess("+path+")");
		
		String buffer = "";
		String response = "";
	    
	    BufferedReader in;
	    PrintWriter out = new PrintWriter(System.out);
	    try {
	        Process p = Runtime.getRuntime().exec(path);
	        in = new BufferedReader(new InputStreamReader(p.getInputStream()));
	        
	        while ((buffer = in.readLine()) != null) {
	          response += buffer + "\r\n";
	          if (output) {
		          out.println(buffer); 
		          out.flush();
	          }
	        }
	    	}
	    catch (IOException e) {
	    		e.printStackTrace();
	    }
	    return response;
	}
	
	private void scanFile(String filepath, String search) {
		//System.out.println("HadoopSystem scanFile: "+filepath);
		try {
           File file = new File(filepath);
           BufferedReader reader = new BufferedReader(new FileReader(file));
           
           String line = "";
           String found = "";
           while((line = reader.readLine()) != null)
           {
               if (line.indexOf(search)>0)
            	   	found = line;
           }
           reader.close();
           
          
           System.out.println(found);
       }
       catch (IOException ioe){
           ioe.printStackTrace();
       }
	}
	
	public void runHadoopMasterStart() {
   		System.out.println("HadoopSystem - startMaster...");
		execProcess(workingDir+Config.hadoopRoot+Config.hadoopDaemon
				+" --config "+workingDir+Config.hadoopRoot+Config.hadoopMasterConfig
				+" start namenode",true);
		execProcess(workingDir+Config.hadoopRoot+Config.hadoopDaemon
				+" --config "+workingDir+Config.hadoopRoot+Config.hadoopMasterConfig
				+" start datanode",true);
		execProcess(workingDir+Config.hadoopRoot+Config.hadoopDaemon
				+" --config "+workingDir+Config.hadoopRoot+Config.hadoopMasterConfig
				+" start secondarynamenode",true);
		
		execProcess(workingDir+Config.hadoopRoot+Config.hadoopDaemon
				+" --config "+workingDir+Config.hadoopRoot+Config.hadoopMasterConfig
				+" start jobtracker",true);
		execProcess(workingDir+Config.hadoopRoot+Config.hadoopDaemon
				+" --config "+workingDir+Config.hadoopRoot+Config.hadoopMasterConfig
				+" start tasktracker",true);
		
	}
	
	public void runHadoopMasterStop() {
		System.out.println("HadoopSystem - stopMaster...");
		execProcess(workingDir+Config.hadoopRoot+Config.hadoopDaemon
				+" --config "+workingDir+Config.hadoopRoot+Config.hadoopMasterConfig
				+" stop tasktracker",true);
		execProcess(workingDir+Config.hadoopRoot+Config.hadoopDaemon
				+" --config "+workingDir+Config.hadoopRoot+Config.hadoopMasterConfig
				+" stop jobtracker",true);
		
		execProcess(workingDir+Config.hadoopRoot+Config.hadoopDaemon
				+" --config "+workingDir+Config.hadoopRoot+Config.hadoopMasterConfig
				+" stop secondarynamenode",true);
		execProcess(workingDir+Config.hadoopRoot+Config.hadoopDaemon
				+" --config "+workingDir+Config.hadoopRoot+Config.hadoopMasterConfig
				+" stop datanode",true);
		execProcess(workingDir+Config.hadoopRoot+Config.hadoopDaemon
				+" --config "+workingDir+Config.hadoopRoot+Config.hadoopMasterConfig
				+" stop namenode",true);
		
	}	
	
	public void runHadoopMasterWebinterfaces() {
		System.out.println("HadoopSystem - Webinterfaces Port Info...");
		//cat ./hadoop-1.0.0/logs/hadoop-*-namenode-*.log | grep "Web-server up at:" | tail -1
		
		scanFile(workingDir+Config.hadoopRoot+"/logs/hadoop-"+username
				+"-namenode-"+hostname.substring(0, hostname.indexOf('.'))+".log",
				"Web-server up at:");
		
		//cat ./hadoop-1.0.0/logs/hadoop-*-jobtracker-*.log | grep "Job History Server web address:" | tail -1
		scanFile(workingDir+Config.hadoopRoot+"/logs/hadoop-"+username
				+"-jobtracker-"+hostname.substring(0, hostname.indexOf('.'))+".log",
				"Job History Server web address:");	
	}	
	
	
	public void runHadoopSlaveStart() {
		new Slave(//"/home/lab406/test.sh",
				workingDir+Config.hadoopRoot+Config.hadoopBin,
				" --config "+workingDir+Config.hadoopRoot+Config.hadoopSlaveConfig
				+" tasktracker");
	}
	
	/*
	public void runHadoopSlaveStop() {
		new Slave("workingDir+Config.hadoopRoot+Config.hadoopDaemon,
				" --config "+workingDir+Config.hadoopRoot+Config.hadoopMasterConfig
				+" stop namenode");
	}*/
}
