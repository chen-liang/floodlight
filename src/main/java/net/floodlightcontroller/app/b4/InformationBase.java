package net.floodlightcontroller.app.b4;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class InformationBase {

	protected static Logger logger;
	CopyOnWriteArrayList<Long> allSwitches;
	ConcurrentHashMap<Long, CopyOnWriteArrayList<Long>> allSwLinks;
	ConcurrentHashMap<String, Long> hostSwitchMap; //key mac add, value swid
	ConcurrentHashMap<String,Long> portSwitchMap;
	
	ConcurrentHashMap<Long, SwitchInfo> allSwitchInfo;
	
	class SwitchInfo {
		long dpid;
		ConcurrentHashMap<Short, Long> peers; //key port id, value peer swid
		
		public SwitchInfo() {
			peers = new ConcurrentHashMap<Short, Long>();
		}
		
		public void addLink(Short localport, Long remoteId) {
			peers.put(localport, remoteId);
		}
	}
	
	public InformationBase() {
		logger = LoggerFactory.getLogger(InformationBase.class);
		allSwitches = new CopyOnWriteArrayList<Long>();
		allSwLinks = new ConcurrentHashMap<Long, CopyOnWriteArrayList<Long>>();
		allSwitchInfo = new ConcurrentHashMap<Long, SwitchInfo>();
		hostSwitchMap = new ConcurrentHashMap<String, Long>();
		portSwitchMap = new ConcurrentHashMap<String, Long>();
	}
	
	public boolean addHostSwitchMap(String mac, Long swid) {
		hostSwitchMap.put(mac, swid);
		logger.info("base adding mac:" + mac + " is at " + swid);
		return true; //might want to return false later
	}
	
	public boolean addSwLink(Long src, Short srcPort, Long dst, Short dstPort) {	
		
		SwitchInfo srcswinfo;
		SwitchInfo dstswinfo;
		if(allSwitchInfo.contains(src)) {
			srcswinfo = allSwitchInfo.get(src);			
		} else {
			srcswinfo = new SwitchInfo();
		}
		srcswinfo.addLink(srcPort, dst);
		allSwitchInfo.put(src, srcswinfo);
		logger.info("adding link from:" + src + "on port " + srcPort + " to " + dst);
		
		if(allSwitchInfo.contains(dst)) {
			dstswinfo = allSwitchInfo.get(dst);
		} else {
			dstswinfo = new SwitchInfo();
		}
		dstswinfo.addLink(dstPort, src);
		allSwitchInfo.put(dst, dstswinfo);
		logger.info("adding link from:" + dst + "on port " + dstPort + " to " + src);
		/*
		CopyOnWriteArrayList<Long> peers;
		CopyOnWriteArrayList<Long> peersInverted;
		if(allSwLinks.containsKey(src)) {
			peers = allSwLinks.get(src);
			if(peers.contains(src)) {
				//logger.info("adding link from:" + src + " to " + dst);
				peers.add(dst);
			} 
		} else {
			peers = new CopyOnWriteArrayList<Long>();
			peers.add(dst);
			allSwLinks.put(src, peers);
		}
		
		if(allSwLinks.containsKey(dst)) {
			peersInverted = allSwLinks.get(dst);
			if(peersInverted.contains(dst)) {
				//logger.info("adding link from:" + dst + " to " + src);
				peersInverted.add(src);
			}
		} else {
			peersInverted = new CopyOnWriteArrayList<Long>();
			peersInverted.add(src);
			allSwLinks.put(dst, peersInverted);
		}
		String s = "";
		for(Long key : allSwLinks.keySet()) {
			s += key + "->" + allSwLinks.get(key).size() + " ";
		}
		logger.info("now we have:" + s);*/
		return true;
	}
	
	public Long getSwitchByMac(String mac) {
		if(!hostSwitchMap.containsKey(mac)) {
			return null;
		} else {
			return hostSwitchMap.get(mac);
		}
	}
	
	public boolean addPortSwitchMap(String mac, Long swid) {
		portSwitchMap.put(mac, swid);
		return true;
	}
}
