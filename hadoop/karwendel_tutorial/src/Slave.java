
import org.globus.cog.abstraction.impl.common.AbstractionFactory;
import org.globus.cog.abstraction.impl.common.StatusEvent;
import org.globus.cog.abstraction.impl.common.task.JobSpecificationImpl;
import org.globus.cog.abstraction.impl.common.task.ServiceContactImpl;
import org.globus.cog.abstraction.impl.common.task.ServiceImpl;
import org.globus.cog.abstraction.impl.common.task.TaskImpl;
import org.globus.cog.abstraction.interfaces.JobSpecification;
import org.globus.cog.abstraction.interfaces.Service;
import org.globus.cog.abstraction.interfaces.Status;
import org.globus.cog.abstraction.interfaces.StatusListener;
import org.globus.cog.abstraction.interfaces.Task;

/*
gridSites.put("karwendel.dps.uibk.ac.at",new GridSite("karwendel.dps.uibk.ac.at","/home/fritz/deployments/povray/povray","/software/ffmpeg/bin/ffmpeg"));
gridSites.put("login.leo1.uibk.ac.at",new GridSite("login.leo1.uibk.ac.at","/home/c703/c703246/povray/bin/povray","/home/c703/c703246/scratch/leo1/ffmpeg/bin/ffmpeg"));
gridSites.put("altix1.uibk.ac.at",new GridSite("altix1.uibk.ac.at","/home/cb56/cb561004/povray/bin/povray","/home/cb56/cb561004/ffmpeg/bin/ffpmeg"));
gridSites.put("alex.jku.austriangrid.at",new GridSite("alex.jku.austriangrid.at","/data1/home/agpool/agp11169/povray/bin/povray","/data1/home/agpool/agp11169/ffmpeg/bin/ffmpeg"));
gridSites.put("lilli.edvz.uni-linz.ac.at",new GridSite("lilli.edvz.uni-linz.ac.at","/usr/bin/povray","/home/agpool/agp11138/ffmpeg/ffmpeg"));
*/

public class Slave {

//	../sbin/hadoop-daemon.sh --config ../etc/hadoop start datanode
//	../sbin/hadoop-daemon.sh --config ../etc/hadoop start tasktracker
	
	public Slave(String exec, String parameter) {
		submitJob(exec, parameter, "slaves.out", "slaves.err","karwendel.dps.uibk.ac.at","sge");
	}
	
	
	public void submitJob(String exec, String args, String stdout, String stderr,
			String hostName, String jobManager) {
		try {
    
            JobSpecification js = new JobSpecificationImpl();
            js.setExecutable(exec);
            js.setArguments(args);
            js.setStdOutput(stdout);
            js.setStdError(stderr);

            Task t = new TaskImpl("test", Task.JOB_SUBMISSION);
            t.setSpecification(js);

            t.setService(Service.JOB_SUBMISSION_SERVICE, new ServiceImpl("gt2",
            		new ServiceContactImpl(hostName+"/jobmanager-"+jobManager), null));
            
            System.out.println("Submitting Job @ "+hostName+"/jobmanager-"+jobManager);
            
            SlaveStatusListener listener = new SlaveStatusListener(this);
            t.addStatusListener(listener);
            
            AbstractionFactory.newExecutionTaskHandler("gt2").submit(t);
               
            //System.out.println("waiting 30sec... and cancel task");
            //Thread.sleep(30000);
            //AbstractionFactory.newExecutionTaskHandler("gt2").cancel(t);
	    
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
	
	
	public static class SlaveStatusListener implements StatusListener {
		private Slave slave;
	
		public SlaveStatusListener(Slave slave) {
			this.slave = slave;
		}
		
        public void statusChanged(StatusEvent event) {
        	
           if (event.getStatus().getStatusCode() == Status.ACTIVE) {
        	   		//Task t = (Task)event.getSource();
             	System.out.println("Job active @ "+event.getSource().getName());
             	
            } else if (event.getStatus().getStatusCode() == Status.COMPLETED) {
            		//Task t = (Task)event.getSource();
            		System.out.println("Job finished @ "+event.getSource().getName());
            	
            } else if (event.getStatus().getStatusCode() == Status.SUBMITTED) {
            } else if (event.getStatus().getStatusCode() == Status.UNSUBMITTED) {
            } else if (event.getStatus().getStatusCode() == Status.UNKNOWN) {
            } else if (event.getStatus().getStatusCode() == Status.CANCELED) {
            	 System.out.println("Job canceled @ "+event.getSource());
            } else if (event.getStatus().getStatusCode() == Status.FAILED) {
            	 System.out.println("Job failed @ "+event.getSource());
            } else {
            	 System.out.println("Warning: got unexpected StatusEvent ("+event.getStatus().getStatusString()+")");
            }
        }
    }
}
