package net.floodlightcontroller.app.b4;

import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;

import net.floodlightcontroller.app.b4.rmi.RemoteLocalClient;

public class LocalRMIImpl extends UnicastRemoteObject implements RemoteLocalClient {

	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	private LocalController controllerRef;

	protected LocalRMIImpl(LocalController lc) throws RemoteException {
		super();
		controllerRef = lc;
	}

	@Override
	public String test() throws RemoteException {
		return "i'm " + controllerRef.getHandler().name;
	}

}
