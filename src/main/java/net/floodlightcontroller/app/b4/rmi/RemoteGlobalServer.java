package net.floodlightcontroller.app.b4.rmi;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.HashMap;
import java.util.LinkedList;

import net.floodlightcontroller.app.b4.LocalHandler;

public interface RemoteGlobalServer extends Remote {
	
	public LocalHandler contact() throws RemoteException;
	
	public boolean addHostSwitchMap(String mac, Long swid, Short port) throws RemoteException;
	
	public boolean addSwLink(Long src, Short srcPort, Long dst, Short dstPort) throws RemoteException;
	
	public Long getSwitchByMac(String mac) throws RemoteException;
	
	public Short getPortOnSwByMac(Long swid, String mac, int id) throws RemoteException;
	
	public boolean addPortSwitchMap(String mac, Long swid, int id) throws RemoteException;
	
	public void sendFlowDemand(HashMap<String, HashMap<String, FlowStatsDesc>> map, int id) throws RemoteException;
	
	public Long getSwidByHostMac(String mac) throws RemoteException;
	
	public Short getPortBySwid(Long srcSwid, Long dstSwid) throws RemoteException;
	
	public void releaseTunnelCapacity(String tid, Long cap) throws RemoteException;
	
	public boolean consumeTunnelCapacity(String tid, Long cap) throws RemoteException;
	
	public Long getTunnelCapacity(String tid) throws RemoteException;
	
	public LinkedList<TunnelInfo> getTunnelInfoBySrcDst(String srcMAC, String dstMAC) throws RemoteException;
	
}
