package net.floodlightcontroller.app.b4;

import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.HashMap;

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
	public LocalHandler contact() throws RemoteException {
		return controllerRef.getNextHandler();
	}

	@Override
	public boolean addHostSwitchMap(String mac, Long swid) throws RemoteException {
		return controllerRef.addHostSwitchMap(mac, swid);
	}
	
	@Override
	public boolean addSwLink(Long src, Short srcPort, Long dst, Short dstPort) throws RemoteException {
		return controllerRef.addSwLink(src, srcPort, dst, dstPort);
	}

	@Override
	public Long getSwitchByMac(String mac) throws RemoteException {
		return controllerRef.getSwitchByMac(mac);
	}

	@Override
	public boolean addPortSwitchMap(String mac, Long swid, int id)
			throws RemoteException {
		return controllerRef.addPortSwitchMap(mac, swid, id);
	}

	@Override
	public void sendFlowDemand(HashMap<String, HashMap<String, Long>> map, int id)
			throws RemoteException {
		controllerRef.sendFlowDemand(map, id);
	}

}
