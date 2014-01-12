package net.floodlightcontroller.app.b4;

import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.HashMap;
import java.util.LinkedList;

import net.floodlightcontroller.app.b4.rmi.RemoteLocalClient;
import net.floodlightcontroller.app.b4.rmi.SwitchFlowGroupDesc;

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

	@Override
	public boolean sendSwFGDesc(
			HashMap<Long, LinkedList<SwitchFlowGroupDesc>> descmap)
			throws RemoteException {
		controllerRef.sendSwFGDesc(descmap);
		return true;
	}

}
