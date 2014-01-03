package net.floodlightcontroller.app.b4;

import java.io.Serializable;

public class LocalHandler implements Serializable {
	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	int id; //id of this handler
	int portToUse; //port that this local controller should use for rmi
	String name; //name to use for rmi
}
