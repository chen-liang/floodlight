package net.floodlightcontroller.app.b4.rmi;

import java.rmi.Remote;
import java.rmi.RemoteException;

public interface RemoteGlobalServer extends Remote {
	
	public int contact() throws RemoteException;
	
}
