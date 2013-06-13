package at.illecker.hama.examples;

import org.apache.hama.pipes.Submitter;

public class TestSubmitter {

	/**
	 * @param args
	 */
	public static void main(String[] args) {
		// TODO Auto-generated method stub
		String[] arr = {
				"-conf",
				"/home/bafu/workspace/applications/bsp/pipes/TestProtocol/TestProtocol_job.xml",
				"-output", "output/TestProtocol" };

		try {
			Submitter.main(arr);

		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

}
