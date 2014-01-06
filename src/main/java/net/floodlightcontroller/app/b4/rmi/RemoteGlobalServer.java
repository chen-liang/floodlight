package net.floodlightcontroller.app.b4.rmi;

import java.rmi.Remote;
import java.rmi.RemoteException;

import net.floodlightcontroller.app.b4.LocalHandler;

public interface RemoteGlobalServer extends Remote {
	
	public LocalHandler contact() throws RemoteException;
	
	public boolean addHostSwitchMap(String mac, Long swid) throws RemoteException;
	
	public boolean addSwLink(Long src, Long dst) throws RemoteException;
	
	public Long getSwitchByMac(String mac) throws RemoteException;
	
	public boolean addPortSwitchMap(String mac, Long swid) throws RemoteException;
	
}
