package net.floodlightcontroller.app.b4.rmi;

import java.rmi.Remote;
import java.rmi.RemoteException;

public interface RemoteLocalClient extends Remote {
	
	public String test() throws RemoteException;

}
