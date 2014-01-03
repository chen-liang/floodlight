package net.floodlightcontroller.app.b4.rmi;

import java.rmi.Remote;
import java.rmi.RemoteException;

import net.floodlightcontroller.app.b4.LocalHandler;
import net.floodlightcontroller.routing.Link;

public interface RemoteGlobalServer extends Remote {
	
	public LocalHandler contact() throws RemoteException;
	
	public boolean addHostSwitchMap(String mac, Long swid) throws RemoteException;
	
	public boolean addSwLink(Link link) throws RemoteException;
	
	public Long getSwitchByMac(String mac) throws RemoteException;
	
}
