package net.floodlightcontroller.app.b4;

import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;

import net.floodlightcontroller.app.b4.rmi.RemoteGlobalServer;

public class GlobalRMIImpl extends UnicastRemoteObject implements RemoteGlobalServer {

	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	GlobalController controllerRef;
	
	protected GlobalRMIImpl(GlobalController con) throws RemoteException {
		super();
		controllerRef = con;
	}

	@Override
	public int contact() throws RemoteException {
		return controllerRef.getNextId();
	}

}
