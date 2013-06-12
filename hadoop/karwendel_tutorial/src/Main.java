import java.util.Scanner;


public class Main {

	public static Config config;

	/**
	 * @param args
	 */
	public static void main(String[] args) {
		
		String currentDir = System.getProperty("user.dir");
		System.out.println("HadoopSystem started @ "+currentDir);
		
		if (args.length > 0) {
		    try {
		        int hdfsPort = Integer.parseInt(args[0]);
		        int jobTrackerPort = 0;
		        if (args.length > 1)
		        		jobTrackerPort = Integer.parseInt(args[1]);
		        config = new Config(currentDir,hdfsPort,jobTrackerPort);
		        
		    } catch (NumberFormatException e) {
		        System.err.println("First argrument must be HDFS Port!");
		        System.err.println("First argrument must be JobTracker Port!");		        
		        System.exit(1);
		    }
		} else 
			config = new Config(currentDir);
		
		printMenue();
	}
	
	private static void printMenue(){
		int menuResult = -1;
		
		while (menuResult!=0) {
			//Clear console
			//try {
			//	Runtime.getRuntime().exec("cls");
			//} catch (IOException e) {}
			// Display menu
		    System.out.println("============================");
		    System.out.println("|   HADOOP MENU SELECTION  |");
		    System.out.println("============================");
		    System.out.println("| Options:                 |");
		    System.out.println("|  0. Exit                 |");
		    System.out.println("|  1. Start Master         |");
		    System.out.println("|  2. Stop  Master         |");		    
		    System.out.println("|  3. Master Web PORTS     |");
		    System.out.println("|  4. Start Slave          |");
		    System.out.println("|  You have to kill Slaves |");
		    System.out.println("|  by running qdel         |");
		    System.out.println("============================");
		    System.out.print("Select option: ");
		    
		    
		    try {
		    		menuResult = new Scanner(System.in).nextInt();
			
		    	} catch (Exception e) {
				//e.printStackTrace();
				continue;
			}
		    
		    
		    // Switch construct
		    switch (menuResult) {
		    		case 0:
		    			System.exit(0);
		    			break;
			   	case 1:
			   		config.runHadoopMasterStart();
			   		break;
			   	case 2:
			   		config.runHadoopMasterStop();
			    		break;
			    case 3:
			    		config.runHadoopMasterWebinterfaces();
			   		break;
			    case 4:
		    			config.runHadoopSlaveStart();
		    			break;
			    default:
			    		System.out.println("Invalid selection");
			    		break; // This break is not really necessary
			}
		}
	}

}
