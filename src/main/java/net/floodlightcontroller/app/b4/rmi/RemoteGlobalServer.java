package net.floodlightcontroller.app.b4.rmi;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.HashMap;

import net.floodlightcontroller.app.b4.LocalHandler;

public interface RemoteGlobalServer extends Remote {
	
	public LocalHandler contact() throws RemoteException;
	
	public boolean addHostSwitchMap(String mac, Long swid) throws RemoteException;
	
	public boolean addSwLink(Long src, Short srcPort, Long dst, Short dstPort) throws RemoteException;
	
	public Long getSwitchByMac(String mac) throws RemoteException;
	
	public boolean addPortSwitchMap(String mac, Long swid, int id) throws RemoteException;
	
	public void sendFlowDemand(HashMap<String, HashMap<String, Long>> map, int id) throws RemoteException;
	
}
