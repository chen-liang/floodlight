package net.floodlightcontroller.app.b4.rmi;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.HashMap;
import java.util.LinkedList;

public interface RemoteLocalClient extends Remote {
	
	public String test() throws RemoteException;
	
	public boolean sendSwFGDesc(HashMap<Long, LinkedList<SwitchFlowGroupDesc>> descmap) throws RemoteException;

}
