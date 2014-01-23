package net.floodlightcontroller.app.b4;

import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.HashMap;
import java.util.LinkedList;

import net.floodlightcontroller.app.b4.rmi.FlowStatsDesc;
import net.floodlightcontroller.app.b4.rmi.RemoteGlobalServer;
import net.floodlightcontroller.app.b4.rmi.TunnelInfo;

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
	public boolean addHostSwitchMap(String mac, Long swid, Short port) throws RemoteException {
		return controllerRef.addHostSwitchMap(mac, swid, port);
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
	public Short getPortOnSwByMac(Long swid, String mac, int id) {
		return controllerRef.getPortOnSwByMac(swid, mac, id);
	}

	@Override
	public boolean addPortSwitchMap(String mac, Short port, Long speedBps, Long swid, int id)
			throws RemoteException {
		return controllerRef.addPortSwitchMap(mac, port, speedBps, swid, id);
	}
	
	@Override
	public void removeSwichFromControoler(Long swid, int id) {
		controllerRef.removeSwichFromControoler(swid, id);
	}

	@Override
	public void sendFlowDemand(HashMap<String, HashMap<String, FlowStatsDesc>> map, int id)
			throws RemoteException {
		controllerRef.sendFlowDemand(map, id);
	}

	@Override
	public Long getSwidByHostMac(String mac) throws RemoteException {
		return controllerRef.getSwidByHostMac(mac);
	}
	
	@Override
	public Short getPortBySwid(Long srcSwid, Long dstSwid) {
		return controllerRef.getPortBySwid(srcSwid, dstSwid);
	}

	@Override
	public void releaseTunnelCapacity(String tid, Long cap)
			throws RemoteException {
		controllerRef.releaseTunnelCapacity(tid, cap);
	}

	@Override
	public boolean consumeTunnelCapacity(String tid, Long cap)
			throws RemoteException {
		return controllerRef.consumeTunnelCapacity(tid, cap);
	}

	@Override
	public Long getTunnelCapacity(String tid) throws RemoteException {
		return controllerRef.getTunnelCapacity(tid);
	}
	
	@Override
	public LinkedList<TunnelInfo> getTunnelInfoBySrcDst(String srcMAC, String dstMAC) {
		return controllerRef.getTunnelInfoBySrcDst(srcMAC, dstMAC);
	}

	@Override
	public boolean portMacNoted(String mac, Long swid, Short port)
			throws RemoteException {
		return controllerRef.portMacNoted(mac, swid, port);
	}

	@Override
	public LinkedList<Long> setMatchToTunnel(Long srcSwid, Long dstSwid,
			String tid, Long bw, int id) {
		return controllerRef.setMatchToTunnel(srcSwid, dstSwid, tid, bw, id);
	}
}
