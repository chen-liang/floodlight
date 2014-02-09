package net.floodlightcontroller.app.b4;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import net.floodlightcontroller.app.b4.rmi.SwitchFlowGroupDesc;
import net.floodlightcontroller.app.b4.rmi.TunnelInfo;
import net.floodlightcontroller.packet.Ethernet;

import org.openflow.protocol.OFMatch;
import org.openflow.util.HexString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class InformationBase {

	protected static Logger logger;
	CopyOnWriteArrayList<Long> allSwitches;
	ConcurrentHashMap<Long, CopyOnWriteArrayList<Long>> allSwLinks;
	ConcurrentHashMap<String, HostSwitchPortPair> hostSwitchMap; //key mac add, value swid
	ConcurrentHashMap<String,PortSwitchPortPair> portSwitchMap;
	
	ConcurrentHashMap<Long, SwitchInfo> allSwitchInfo;
	
	ConcurrentHashMap<Integer, CopyOnWriteArrayList<Long>> localControllerSwMap;
	
	ConcurrentHashMap<String, FlowGroup> allFGs; //key is name,
	ConcurrentHashMap<String, TunnelGroup> allTGs;
	ConcurrentHashMap<String, Tunnel> allTs;
	ConcurrentHashMap<String, String> TtoTGMap;
	
	ConcurrentHashMap<String, Long> allFGBW; //key is name, value is the BW we give it
	ConcurrentHashMap<String, Long> allTBW;
	CopyOnWriteArrayList<String> fullTunnels;
	ConcurrentHashMap<Long, CopyOnWriteArrayList<Long>> fullLinks;
	//key is fgid, value is <key is tid, value is bw on that tunnel for fgids>
	ConcurrentHashMap<String, ConcurrentHashMap<String, Long>> FgToTunnelMap; 
	
	ConcurrentHashMap<Long, ConcurrentHashMap<Long, LinkedList<String>>> linkToTunnelMap;
	ConcurrentHashMap<Long, ConcurrentHashMap<Long, Long>> linkCapacities;
	
	ConcurrentHashMap<OFMatch, Long> flowByteCount;
	ConcurrentHashMap<OFMatch, String> matchFGMap;
	ConcurrentHashMap<String, LinkedList<OFMatch>> fgMatches;
	
	
	//stores which link is on which tunnel
	//key is dpid, value is <dpid, tunnelid>
	//NOTE, a link swid1 <-> swid2 needs to be stored twice
	//but to save space, only store once: always use lower 
	//swid as the key to eliminate ambiguity
	ConcurrentHashMap<Long, ConcurrentHashMap<Long, LinkedList<String>>> swidTunnelMap;
	
	
	HashMap<Long, LinkedList<SwitchFlowGroupDesc>> currDescmap;
	HashMap<Integer, HashMap<Long, LinkedList<SwitchFlowGroupDesc>>> currControllerSwitchFGDesc;
	
	ConcurrentHashMap<Long, ConcurrentHashMap<Long, TunnelSwitchListPair>> matchToTunnelMap;
	
	Long linkCap;
	Long fgCap;
	
	class HostSwitchPortPair {
		Long swid;
		Short port;
	}
	
	class PortSwitchPortPair {
		Long swid;
		Short port;
		Long speedInBps;
	}
	
	class TunnelSwitchListPair {
		String tid;
		LinkedList<Long> swids;
		
		public TunnelSwitchListPair() {
			swids = new LinkedList<Long>();
		}
	}
	
	class SwitchInfo {
		long dpid;
		ConcurrentHashMap<Short, Long> peers; //key port id, value peer swid
		ConcurrentHashMap<Long, Short> peersInverted; //key peer swid, value port 
		ConcurrentHashMap<Short, Long> portBw; //key port id, value bw in Bps
		
		public SwitchInfo() {
			peers = new ConcurrentHashMap<Short, Long>();
			peersInverted = new ConcurrentHashMap<Long, Short>();
			portBw = new ConcurrentHashMap<Short, Long>();
		}
		
		public void addLink(Short localport, Long remoteId) {
			//logger.info(dpid + " adding to " + " remoteid:" + remoteId + " on " + localport);
			peers.put(localport, remoteId);
			int count = 0;				
			for(short port : peers.keySet()) {
				if(peers.get(port).equals(remoteId)) {
					count ++;
				}
			}
			if(count != 1) {
				logger.info("NOTE PANIC! this switch has more than 1 port connect to same peer switch?! " + peers);
			} else {
				peersInverted.put(remoteId, localport);
			}
		}
		
		public void addPortBw(Short id, Long bw) {
			portBw.put(id, bw);
		}
		
		public Long getBwByDst(Long dstSwid) {
			if(!peersInverted.containsKey(dstSwid)) {
				logger.info("NOTE on " + dpid + " asking for port to:" + dstSwid + " but no port to that!");
				return null;
			}
			Short port = peersInverted.get(dstSwid);
			if(!portBw.containsKey(port)) {
				logger.info("NOTE on " + dpid + " said that there is port to " + dstSwid + " but no Bw info!!");
				return Long.MAX_VALUE;
			}
			return portBw.get(port);
		}
	}	
	
	//Be aware of the difference between FG and TG here! (as they have same para)
	//in FG, this is the flows we want to assign and FG refers to all the paths
	//avaliable for given src and dst
	class FlowGroup {
		//in B4, both src and dst are *site*, here we treat a sw
		//as equal to a site, all flows from src sw to dst sw
		//are put into a group(even though the flows may have different
		//srcIP dstIP pairs)
		Long srcSwid;
		Long dstSwid;
		//here id is to differentiate FGs that have the same src and dst
		//equal to the QoS field in B4 paper
		String id; 
		
		Long demand;
	}
	
	class TunnelGroup {
		//Long srcSwid;
		//Long dstSwid;
		String id; // maybe only for debugging purpose 
		
		Long capacity;
		
		CopyOnWriteArrayList<String> allTunnels;
		CopyOnWriteArrayList<String> currentFGs;
		
		public TunnelGroup() {
			allTunnels = new CopyOnWriteArrayList<String>();
			currentFGs = new CopyOnWriteArrayList<String>();
		}
	}
	
	class Tunnel {
		//should be a path, how to represent?
		LinkedList<Long> path;
		String id;
		
		Long capacity;

		Long srcSwid;
		Long dstSwid;
		
		public Tunnel() {
			path = new LinkedList<Long>();
		}
		
		@Override
		public String toString() {
			String s = "src:" + srcSwid + "->" + dstSwid;
			for(Long swid : path) {
				s += "::" + swid;
			}
			return s;
		}
	}
		
	public InformationBase() {
		logger = LoggerFactory.getLogger(InformationBase.class);
		allSwitches = new CopyOnWriteArrayList<Long>();
		allSwLinks = new ConcurrentHashMap<Long, CopyOnWriteArrayList<Long>>();
		allSwitchInfo = new ConcurrentHashMap<Long, SwitchInfo>();
		hostSwitchMap = new ConcurrentHashMap<String, HostSwitchPortPair>();
		portSwitchMap = new ConcurrentHashMap<String, PortSwitchPortPair>();
		localControllerSwMap = new ConcurrentHashMap<Integer, CopyOnWriteArrayList<Long>>();
		allFGs = new ConcurrentHashMap<String, FlowGroup>();
		allTGs = new ConcurrentHashMap<String, TunnelGroup>();
		allTs = new ConcurrentHashMap<String, Tunnel>();
		allFGBW = new ConcurrentHashMap<String, Long>();
		allTBW = new ConcurrentHashMap<String, Long>();
		TtoTGMap = new ConcurrentHashMap<String, String>();
		swidTunnelMap = new ConcurrentHashMap<Long, ConcurrentHashMap<Long,LinkedList<String>>>();
		flowByteCount = new ConcurrentHashMap<OFMatch, Long>();
		FgToTunnelMap = new ConcurrentHashMap<String, ConcurrentHashMap<String, Long>>();
		linkToTunnelMap = new ConcurrentHashMap<Long, ConcurrentHashMap<Long,LinkedList<String>>>();
		fullTunnels = new CopyOnWriteArrayList<String>();
		fullLinks = new ConcurrentHashMap<Long, CopyOnWriteArrayList<Long>>();
		matchFGMap = new ConcurrentHashMap<OFMatch, String>();
		fgMatches = new ConcurrentHashMap<String, LinkedList<OFMatch>>();
		linkCapacities = new ConcurrentHashMap<Long, ConcurrentHashMap<Long, Long>>();
		matchToTunnelMap = new ConcurrentHashMap<Long, ConcurrentHashMap<Long, TunnelSwitchListPair>>();
	}
	
	private Long getLinkCapacity(Long id1, Long id2) {
		Long lowerid = id1>id2?id2 : id1;
		Long higherid = lowerid == id1?id2 : id1;
		if(!linkCapacities.containsKey(lowerid) || 
				!linkCapacities.get(lowerid).containsKey(higherid))
			return linkCap;
		return linkCapacities.get(lowerid).get(higherid);
	}
	
	private void putLinkCapacity(Long id1, Long id2, Long newcap) {
		Long lowerid = id1>id2?id2 : id1;
		Long higherid = lowerid == id1?id2 : id1;
		if(linkCapacities.containsKey(lowerid)) {
			ConcurrentHashMap<Long, Long> map = linkCapacities.get(lowerid);
			map.put(higherid, newcap);
		} else {
			ConcurrentHashMap<Long, Long> map =
					new ConcurrentHashMap<Long, Long>();
			map.put(higherid, newcap);
			linkCapacities.put(lowerid, map);									
		}
		
	}
	
	private void markLinkFull(Long id1, Long id2) {
		Long lowerid = id1>id2?id2 : id1;
		Long higherid = lowerid == id1?id2 : id1;
		if(fullLinks.containsKey(lowerid)) {
			if(fullLinks.get(lowerid).contains(higherid)) {
				logger.info("NOTE: re-setting full link!!!!");
				return;
			}
			fullLinks.get(lowerid).add(higherid);
		} else {
			CopyOnWriteArrayList<Long> list = new CopyOnWriteArrayList<Long>();
			list.add(higherid);
			fullLinks.put(lowerid, list);
		}
		
	}
	
	public void addByteCount(OFMatch match, Long byteCount) {
		flowByteCount.put(match, byteCount);
		logger.info("adding byte count:" + match.toString() + " :" + byteCount + "--------");
		lookupFgForMatch(match);
	}
	
	public boolean addHostSwitchMap(String mac, Long swid, Short port) {
		//NOTE tricky thing here is that, it could be a switch not under control of that local controller
		//UPDATE given the new detect packet, maybe this would not happen
		String s = ">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>add host to switch:" + mac + " seen on:" + swid + ":p:" + port;
		if(portSwitchMap.containsKey(mac)) {
			//this is actually a switch!!
			//in this case, it means: on the *port* of switch *swid*, see another port 
			//whose mac is *mac*, add this info on both switch(maybe no need to be on both?)
			PortSwitchPortPair pair = portSwitchMap.get(mac);
			SwitchInfo sinfo = allSwitchInfo.get(swid);
			sinfo.addLink(port, pair.swid);
			SwitchInfo peerinfo = allSwitchInfo.get(pair.swid);
			peerinfo.addLink(pair.port, swid);
			s += " it is a switch!!!! ";
			logger.info(s);
			return true;
		} else {
			//not a switch at this point, but maybe later on will figure out that it a switch
			if(hostSwitchMap.containsKey(mac)) {
				logger.debug("NOTE already seen this host on:" + hostSwitchMap + " want it on?:" + swid);
				return false;
			}
			HostSwitchPortPair pair = new HostSwitchPortPair();
			pair.swid = swid;
			pair.port = port;
			hostSwitchMap.put(mac, pair);
			logger.info("base adding mac:" + mac + " is at " + swid);
			s += " it is a host";
			logger.info(s);
			return true; //might want to return false later
		}
	}
	
	//return true means local controller can send this info
	//a second time, return false means this mac is already 
	//processed, do not send again
	public boolean portMacNoted(String mac, Long swid, Short port) {
		if(!portSwitchMap.containsKey(mac)) {
			//local said *port* on *swid* is connected to a port with *mac*
			//but i don't even know *mac*!! the reason could be there is delay
			//so do nothing at this point
			return true;
		}
		//add mutual link to sws, at this point, *swid* is guaranteed
		//to be known by global already, but due to delay, pair.swid
		//might be unknown, but since pair is known, swid should also
		//be known too
		PortSwitchPortPair pair = portSwitchMap.get(mac);
		SwitchInfo sw1 = allSwitchInfo.get(pair.swid);
		sw1.addLink(pair.port, swid);
		

		SwitchInfo sw2 = allSwitchInfo.get(swid);
		sw2.addLink(port, pair.swid);
		return false;
	}
	
	public boolean addPortSwitchMap(String mac, Short port, Long speedInBps, Long swid) {
		if(portSwitchMap.containsKey(mac)) {
			logger.info("NOTE adding duplicate port mac address:" + mac + " port:" + port);
			return false;
		}
		PortSwitchPortPair pair = new PortSwitchPortPair();
		pair.swid = swid;
		pair.port = port;
		pair.speedInBps = speedInBps;
		portSwitchMap.put(mac, pair);
		if(!allSwitchInfo.containsKey(swid)) {
			SwitchInfo info = new SwitchInfo();
			allSwitchInfo.put(swid, info);
		}
		allSwitchInfo.get(swid).addPortBw(port, speedInBps);
		//tricky thing here is that we might already see this mac
		//but back then, we did not know it is switch and added it
		//as a host, if that so, need to fix that, otherwise, do nothing
		if(hostSwitchMap.containsKey(mac)) {
			logger.info(mac + " is not a host! remove from host map");
			hostSwitchMap.remove(mac);
		}
		//update tunnel capacity if needed
		
		return true;
	}
	
	public Short getPortOnSwByMac(Long swid, String mac, int id) {
		if(!hostSwitchMap.containsKey(mac)) {
			logger.info("NOTE asking for non-existence mac address from " + id);
			return null;
		}
		if(!hostSwitchMap.get(mac).swid.equals(swid)) {
			String s = "found mac" + mac + " on " + hostSwitchMap.get(mac).swid 
					+ " but supposed on " + swid; 
			logger.info(s);
			return null;
		}
		return hostSwitchMap.get(mac).port;
	}
	
	public boolean addControllerSwMap(Long swid, int id) {
		if(localControllerSwMap.containsKey(id)){
			CopyOnWriteArrayList<Long> swids = localControllerSwMap.get(id);
			//there might be duplicate call that add the same sw
			//this is because this method is called per switch port
			if(!swids.contains(swid))
				swids.add(swid);
		} else {
			CopyOnWriteArrayList<Long> swids = new CopyOnWriteArrayList<Long>();
			swids.add(swid);
			localControllerSwMap.put(id, swids);
		}
		return true;
	}
	
	public void removeSwichFromControoler(Long swid, int id) {
		localControllerSwMap.get(id).remove(swid);
	}
	
	public Short getPortBySwid(Long srcSwid, Long dstSwid) {
		if(!allSwitchInfo.containsKey(srcSwid)) {
			logger.info(srcSwid + " no such src:" + allSwitchInfo.keySet());
			return null;
		}
		SwitchInfo info = allSwitchInfo.get(srcSwid);
		if(!info.peersInverted.containsKey(dstSwid)) {
			logger.info("for src:" + srcSwid + " no such dst:" + dstSwid + " all dst:" + info.peersInverted.keySet() + ":" + info.peers.keySet());
			return null;
		}
		return info.peersInverted.get(dstSwid);
	}
	
	public boolean addSwLink(Long src, Short srcPort, Long dst, Short dstPort) {	
		
		SwitchInfo srcswinfo;
		SwitchInfo dstswinfo;
		if(allSwitchInfo.containsKey(src)) {
			srcswinfo = allSwitchInfo.get(src);			
		} else {
			srcswinfo = new SwitchInfo();
		}
		srcswinfo.addLink(srcPort, dst);
		allSwitchInfo.put(src, srcswinfo);
		logger.info("adding link from:" + src  + " to " + dst + " on port " + srcPort);
		
		if(allSwitchInfo.containsKey(dst)) {
			dstswinfo = allSwitchInfo.get(dst);
		} else {
			dstswinfo = new SwitchInfo();
		}
		dstswinfo.addLink(dstPort, src);
		allSwitchInfo.put(dst, dstswinfo);
		logger.info("adding link from:" + dst  + " to " + src + "on port " + dstPort);
		return true;
	}
	
	public Long getSwitchByMac(String mac) {
		if(!hostSwitchMap.containsKey(mac)) {
			return null;
		} else {
			return hostSwitchMap.get(mac).swid;
		}
	}
	

	private void addFGtoTunnel(String fgid, String tid, Long bw) {
		logger.info("allocate fg " + fgid + " to tunnel " + tid + " with bw " + bw);
		if(FgToTunnelMap.containsKey(fgid)) {
			ConcurrentHashMap<String, Long> bwmap = FgToTunnelMap.get(fgid);
			if(bwmap.containsKey(tid)) {
				//allocated more on the same tunnel
				bwmap.put(tid, bwmap.get(tid) + bw);
			} else {
				bwmap.put(tid, bw);
			}
		} else {
			ConcurrentHashMap<String, Long> bwmap = new ConcurrentHashMap<String, Long>();
			bwmap.put(tid, bw);
			FgToTunnelMap.put(fgid, bwmap);
		}
	}
	
	private void addAllFGsToTuneel(HashMap<String, String> preference, HashMap<String, Long> bwmap) {
		for(String fgid : bwmap.keySet()) {
			if(!preference.containsKey(fgid)) {
				logger.info("NOTE allocated bw but no tunnel???" + fgid);
				continue;
			}
			String tid = preference.get(fgid);
			Long bw = bwmap.get(fgid);
			addFGtoTunnel(fgid, tid, bw);
		}
	}
	
	private HashMap<String, String> 
	invertPreferenceMap(HashMap<String, LinkedList<String>> preference) {
		HashMap<String, String> invertedMap = new HashMap<String, String>();
		for(String tid : preference.keySet()) {
			LinkedList<String> list = preference.get(tid);
			for(String fgid : list) {
				invertedMap.put(fgid, tid);
			}
		}
		return invertedMap;		
	}
	
	private LinkedList<String> updateLinkCapByFG(HashMap<String, String> fgTunnelMap,
			HashMap<String, Long> fgBwMap) {
		LinkedList<String> fullTunnel = new LinkedList<String>();
		for(String fgid : fgBwMap.keySet()) {
			if(!fgTunnelMap.containsKey(fgid)) {
				logger.info("NOTE allocated BW to fg when it has no tunnel!!!");
				continue;
			}
			String tid = fgTunnelMap.get(fgid);
			Long bw = fgBwMap.get(fgid);
			Tunnel tunnel = allTs.get(tid);
			LinkedList<Long> path = tunnel.path;
			if(path.size() <= 1) {
				logger.info("NOTE a length 1 path!!! What's this???");
				continue;
			}
			for(int i = 0;i<path.size() - 1;i++) {
				Long id1 = path.get(i);
				Long id2 = path.get(i+1);
				Long currBw = getLinkCapacity(id1, id2);
				if(currBw < bw) {
					logger.info("NOTE link cap negative!!!!!!!");
				}
				/*
				if(currBw < bw) {
					markLinkFull(id1, id2);
				}*/
				putLinkCapacity(id1, id2, currBw - bw);
				logger.info("tid:" + tid + "fid:" + fgid + "..>><<update link cap:(" + id1 + "->"
						+ id2 + ") to " + (currBw - bw) + " reduced " + bw);
				if(currBw - bw == 0) {
					Long lowerid = id1 > id2?id2 : id1;
					Long higherid = lowerid == id1?id2 : id1;
					markLinkFull(id1, id2);
					LinkedList<String> tids = linkToTunnelMap.get(lowerid).get(higherid);
					for(String localtid : tids) {
						//remove all tunnels that use this link
						fullTunnel.add(localtid);
					}
				}
			}
		}
		return fullTunnel;
	}
	
	class PreferenceCompartor implements Comparator<PreferenceHelper> {

		@Override
		public int compare(PreferenceHelper o1, PreferenceHelper o2) {
			// TODO Auto-generated method stub
			LinkedList<String> fgids1 = o1.preference;
			LinkedList<String> fgids2 = o2.preference;
			int demand1 = 0;
			int demand2 = 0;
			for(String fgid : fgids1) {
				demand1 += getFGCurrDemand(fgid);
			}
			for(String fgid : fgids2) {
				demand2 += getFGCurrDemand(fgid);
			}
			return  demand2 - demand1; //because default is ascendent order 
		}
	}
	
	class PreferenceHelper {
		public Long lowerid;
		public Long higherid;
		public LinkedList<String> preference;
		
		PreferenceHelper(Long id1, Long id2, LinkedList<String> preference) {
			this.lowerid = id1 > id2?id2 : id1;
			this.higherid = id1 == lowerid?id2 : id1;
			this.preference = preference;
		}
	}
	
	private LinkedList<PreferenceHelper> sort(HashMap<Long, HashMap<Long, LinkedList<String>>> linkPreference) {
		
		LinkedList<PreferenceHelper> allPreference = 
				new LinkedList<InformationBase.PreferenceHelper>();
		for(Long id1 : linkPreference.keySet()) {
			for(Long id2 : linkPreference.get(id1).keySet()) {
				PreferenceHelper helper = 
						new PreferenceHelper(id1, id2, linkPreference.get(id1).get(id2));
				allPreference.add(helper);
			}
		}
		
		Collections.sort(allPreference, new PreferenceCompartor());
		return allPreference;
	}
	
	private void computeTGBWallocation(TunnelGroup tunnelgroup) {
		HashMap<String, Long> currTunnelCap = new HashMap<String, Long>();
		LinkedList<String> currFGsNeedBW = new LinkedList<String>();
		LinkedList<String> currAvaTunnel = new LinkedList<String>();
		
		for(String t : tunnelgroup.allTunnels) {
			currTunnelCap.put(t, allTs.get(t).capacity);
			currAvaTunnel.add(t);
		}
		
		for(String f : tunnelgroup.currentFGs) {
			currFGsNeedBW.add(f);
		}
		
		while(currAvaTunnel.size() > 0 && currFGsNeedBW.size() > 0) {
			//compute fg's preferred tunnel
			HashMap<String, LinkedList<String>> preference =
					computeFGpreference(currAvaTunnel, currFGsNeedBW);

			//if some fg does not appear in all tunnel preferences,
			//the only reason at this point, is because it is impossible
			//for any tunnel to satisfy this fg!!! 
			//So, we stop trying to give it more, what it has so far is
			//what it will be granted
			LinkedList<String> fgsCanBeSatisfied = new LinkedList<String>();
			for(String tid : preference.keySet()) {
				LinkedList<String> list = preference.get(tid);
				fgsCanBeSatisfied.addAll(list);
			}
			for(int i = currFGsNeedBW.size() - 1;i>=0;i--) {
				if(!fgsCanBeSatisfied.contains(currFGsNeedBW.get(i))) {
					logger.info("THIS FG CAN NOT BW SATISFIED:" + currFGsNeedBW.get(i));
					currFGsNeedBW.remove(i);
				}
			}
			//according to tunnel preference, transform it to preference on link
			HashMap<Long, HashMap<Long, LinkedList<String>>> linkPreference = 
					createLinkPeference(preference);
			//compute the order we process the link, the one with greatest load
			//comes first
			LinkedList<PreferenceHelper> prflist = sort(linkPreference);
			String s = "";
			for(PreferenceHelper helper : prflist) {
				s += "(" + helper.lowerid + "->" + helper.higherid + ")";
			}
			logger.info(s);
			//now for each link, compute fair-share and allocate bw accordingly
			for(int i = 0;i<prflist.size();i++) {

				HashMap<String, String> preferenceMap = invertPreferenceMap(preference);
				Long lowerid = prflist.get(i).lowerid;
				Long higherid = prflist.get(i).higherid;
				//go through each link
				//for a link, look at all the fgs that go through this link
				//compute the bw for each of them
				HashMap<String, Long> allocMap = 
						computeLinkBWallocation(lowerid, higherid, prflist.get(i).preference);
				////////////////////////////
				logger.info("for (" + lowerid + "->" + higherid + ")");					
				for(String key : allocMap.keySet()) {
					logger.info("alloc:" + key + " bw:" + allocMap.get(key));
				}
				//based on the bw allocation, we can compute at this point
				//which links are full, and thus compute the tunnels that
				//are full
				/* used to be
				 * LinkedList<String> tids = linkToTunnelMap.get(lowerid).get(higherid);
						for(String tid : tids) {
							//remove all tunnels that use this link
							currAvaTunnel.remove(tid);
						}
				 * not sufficient, because a link full means some other links are also
				 * full as long as this link is not the only link in all tunnel!!
				 */
				LinkedList<String> fullTunnels = 
						updateLinkCapByFG(preferenceMap, allocMap);
				////////////////
				addAllFGsToTuneel(preferenceMap, allocMap);
				///////////////
				for(String fullTunnel : fullTunnels) {
					if(!currAvaTunnel.contains(fullTunnel)) {
						logger.info("NOTE: removing non-exist tunnel:" + fullTunnel);
						continue;
					}
					//remove the full tunnels from avaliable tunnels
					currAvaTunnel.remove(fullTunnel);
					logger.info("remove full tunnel " + fullTunnel + " now have " + currAvaTunnel);
					//ALSO MAKE ALL LINKS OF THIS TUNNEL FROM LINK UNAVALIABLE
					LinkedList<String> failFGs = preference.get(fullTunnel);
					if(failFGs == null || failFGs.size() == 0) {
						logger.info("NOTE no body prefer this tunnel but it's full???" + fullTunnel);
					} else {
						for(String failFgid : failFGs) {
							for(PreferenceHelper phelper : prflist) {
								if(phelper.preference.contains(failFgid)) {
									phelper.preference.remove(failFgid);
									logger.info("Allocation will fail, remove " + failFgid + " from (" 
									+ phelper.lowerid + "," + phelper.higherid + ")");
								}
							}
						}
					}
				}
				//after removal, check whether there are avaliable tunnels
				//if not, stop
				//I think this safe, because preference is sorted on demand,
				//it could happen that some links are congested making other
				//links on the same tunnel useless. In this case, no need to
				//allocate bw, on these other tunnels
				if(currAvaTunnel.size() == 0) {
					logger.info("NO MORE TUNNEL AVALIABLE, BREAK==>");
					break;
				}

				for(String fgid : tunnelgroup.currentFGs) {
					if(getFGCurrDemand(fgid) == 0) {
						//------------demand met
						currFGsNeedBW.remove(fgid);
						if(currFGsNeedBW.size() == 0) {
							break;
						}
					}
				}
			}
			logger.info("After one iteration---------------------------------->");
			logger.info("Full links:");
			for(Long lowerid : fullLinks.keySet()) {
				CopyOnWriteArrayList<Long> dstid = fullLinks.get(lowerid);
				logger.info("(" + lowerid + "," + dstid + ")");
			}
			logger.info("FG allocations:");
			for(String fgid : FgToTunnelMap.keySet()) {
				ConcurrentHashMap<String, Long> alloc = FgToTunnelMap.get(fgid);
				String ss = "For fg " + fgid + ":";
				for(String tid : alloc.keySet()) {
					ss += tid + "->" + alloc.get(tid) + " ";
				}
				logger.info(ss);
			}
		}		
		
		logger.info("FG bw allocation finished!!");
		for(String fgid : allFGBW.keySet()) {
			logger.info(fgid + "->" + allFGBW.get(fgid));
		}
		
	}
	
	public HashMap<Integer, HashMap<Long, LinkedList<SwitchFlowGroupDesc>>> getSwitchFGDesc() {
		return currControllerSwitchFGDesc;
	}
	
	public HashMap<Long, LinkedList<SwitchFlowGroupDesc>> getDescriptionMap() {
		return currDescmap;
	}
	
	//input key = swid, value = swdesclist
	private HashMap<Integer, HashMap<Long, LinkedList<SwitchFlowGroupDesc>>> 
	generateFGDescForAllController(HashMap<Long, LinkedList<SwitchFlowGroupDesc>> descmap) {
		
		HashMap<Integer, HashMap<Long, LinkedList<SwitchFlowGroupDesc>>> controllerSwitchFGDesc = 
				new HashMap<Integer, HashMap<Long,LinkedList<SwitchFlowGroupDesc>>>();
		
		for(Integer conid : localControllerSwMap.keySet()) {
			CopyOnWriteArrayList<Long> swids = localControllerSwMap.get(conid);
			logger.info("now comes to con:" + conid + " with sw:" + swids);
			for(Long currswid : swids) {
				logger.info("now comes to sw:" + currswid);
				if(!descmap.containsKey(currswid)) 
					continue;
				//add the desc to the controller-sw map!!
				if(controllerSwitchFGDesc.containsKey(conid)) {
					HashMap<Long,LinkedList<SwitchFlowGroupDesc>> currConSwMap = 
							controllerSwitchFGDesc.get(conid);
					if(currConSwMap.containsKey(currswid)) {
						currConSwMap.get(currswid).addAll(descmap.get(currswid));
						logger.info("now it is2 ::::" + currConSwMap.get(currswid));
					} else {
						LinkedList<SwitchFlowGroupDesc> desclist = new LinkedList<SwitchFlowGroupDesc>();
						desclist.addAll(descmap.get(currswid));
						currConSwMap.put(currswid, desclist);
						logger.info("now it is ::::" + currConSwMap.get(currswid));
					}
				} else {
					LinkedList<SwitchFlowGroupDesc> desclist = new LinkedList<SwitchFlowGroupDesc>();
					desclist.addAll(descmap.get(currswid));
					HashMap<Long,LinkedList<SwitchFlowGroupDesc>> currConSwMap = new HashMap<Long, LinkedList<SwitchFlowGroupDesc>>();
					currConSwMap.put(currswid, desclist);
					controllerSwitchFGDesc.put(conid, currConSwMap);

					logger.info("now it is3 ::::" + currConSwMap.get(currswid));
				}
			}
		}
		return controllerSwitchFGDesc;
	}
	
	private HashMap<Long, LinkedList<SwitchFlowGroupDesc>> computeFlowInstallation() {
		HashMap<Long, LinkedList<SwitchFlowGroupDesc>> swfgmap = new HashMap<Long, LinkedList<SwitchFlowGroupDesc>>();
		for(String fgid : FgToTunnelMap.keySet()) {
			ConcurrentHashMap<String, Long> map = FgToTunnelMap.get(fgid);
			for(String tid : map.keySet()) {
				Long bw = map.get(tid);
				LinkedList<Long> allsws = allTs.get(tid).path;
				for(int i = 0;i<allsws.size() - 1;i++) {
					Long id1 = allsws.get(i);
					Long id2 = allsws.get(i + 1);
					Long fgSrcSwid = allFGs.get(fgid).srcSwid;
					Long fgDstSwid = allFGs.get(fgid).dstSwid;
					SwitchFlowGroupDesc desc = 
							new SwitchFlowGroupDesc(id1, id2, bw, fgSrcSwid, fgDstSwid, fgMatches.get(fgid));
					if(swfgmap.containsKey(id1)) {
						swfgmap.get(id1).add(desc);
					} else {
						LinkedList<SwitchFlowGroupDesc> list = new LinkedList<SwitchFlowGroupDesc>();
						list.add(desc);
						swfgmap.put(id1, list);
					}
				}
			}
		}
		logger.info("the list:");
		for(Long srcid : swfgmap.keySet()) {
			LinkedList<SwitchFlowGroupDesc> descl = swfgmap.get(srcid);
			for(SwitchFlowGroupDesc desc : descl)
				logger.info(desc.getSrc() + "->" + desc.getDst() + " " 
			+ desc.getFgSrcSwid() + "--->" + desc.getFgDstSwid() + ":" + desc.getBw());
		}
		return swfgmap;
	}
	
	/*
	 * return a map where key = linklowerswid, value = <key = linkhigherid, value = list of fg prefer this link>
	 */
	private HashMap<Long, HashMap<Long, LinkedList<String>>> createLinkPeference(
			HashMap<String, LinkedList<String>> tunnelPreference) {
		
		HashMap<Long, HashMap<Long, LinkedList<String>>> linkPreference = 
				new HashMap<Long, HashMap<Long,LinkedList<String>>>();
		
		//look at each of links that belong to any tunnel, if there is tunnel preference
		//on that tunnel, the fg prefer the tunnel would prefer this link
		for(Long lowerid : linkToTunnelMap.keySet()) {
			ConcurrentHashMap<Long, LinkedList<String>> linkdstMap = linkToTunnelMap.get(lowerid);
			
			for(Long higherid : linkdstMap.keySet()) {
				LinkedList<String> tunnellist = linkToTunnelMap.get(lowerid).get(higherid);
				/*
				 * go through all the tunnels that use this link
				 */
				for(String tunnelid : tunnellist) {
					if(!tunnelPreference.containsKey(tunnelid)) //no fg interested in this tunnel, skip it
						continue;
					/*
					 * then add all the fgs that use this tunnel to link pref
					 */
					LinkedList<String> fgPreferThisTunnel = tunnelPreference.get(tunnelid);
					if(linkPreference.containsKey(lowerid)) {
						if(linkPreference.get(lowerid).containsKey(higherid)) {
							//if there already exists entries that prefer this link
							LinkedList<String> previousFGs = linkPreference.get(lowerid).get(higherid);
							for(String s : fgPreferThisTunnel) {
								if(previousFGs.contains(s)) {
									logger.info("NOTE adding duplicate fg entry!" + s + " to " + previousFGs);
									continue;
								}
								previousFGs.add(s);
							}
							linkPreference.get(lowerid).put(higherid, previousFGs);
						} else {
							LinkedList<String> newFGs = new LinkedList<String>();
							for(String s : fgPreferThisTunnel) {
								if(newFGs.contains(s)) {
									logger.info("NOTE adding duplicate fg entry!" + s + " to " + newFGs);
									continue;
								}
								newFGs.add(s);
							}							
							linkPreference.get(lowerid).put(higherid, newFGs);
						}
					} else {
						//create entry
						HashMap<Long,LinkedList<String>> newmap = new HashMap<Long, LinkedList<String>>();
						LinkedList<String> newFGs = new LinkedList<String>();
						for(String s : fgPreferThisTunnel) {
							newFGs.add(s);
						}
						newmap.put(higherid, newFGs);
						linkPreference.put(lowerid, newmap);
					}
				}
			}
		}
		
		for(Long lowerid : linkPreference.keySet()) {
			for(Long higherid : linkPreference.get(lowerid).keySet()) {
				String s = "for the link from " + lowerid + " to " + higherid + " the # of preference:"
						+ (linkPreference.get(lowerid).get(higherid) == null?"0!":linkPreference.get(lowerid).get(higherid).size());
				s += " while # of tunnel using this link:" + linkToTunnelMap.get(lowerid).get(higherid).size();
				logger.info(s);
			}
		}
		return linkPreference;
	}
	
	
	private Long getFGCurrDemand(String fgid) {
		if(allFGBW.containsKey(fgid)) {
			return allFGs.get(fgid).demand - allFGBW.get(fgid);
		} else {
			return allFGs.get(fgid).demand;
		}
	}
	
	private void addFGBW(String fgid, Long bw) {
		if(!allFGBW.containsKey(fgid)) {
			allFGBW.put(fgid, bw);
		} else {
			allFGBW.put(fgid, allFGBW.get(fgid) + bw);
		}
	}
	
	//return a map, key is tunnel id, value is a list of fgs that prefer it
	//NOTE assumes all ts in *tunnels* are available  
	private HashMap<String, LinkedList<String>> computeFGpreference(LinkedList<String> tunnels, LinkedList<String> fgs) {
		//ideally, should not be computed but should be configured...
		HashMap<String, LinkedList<String>> preference = new HashMap<String, LinkedList<String>>();

		for(String fgid : fgs) {
			FlowGroup fg = allFGs.get(fgid);
			Tunnel currentbestTunnel = null;
			for(String tid : tunnels) {
				Tunnel tunnel = allTs.get(tid);
				if(!tunnel.srcSwid.equals(fg.srcSwid) || !tunnel.dstSwid.equals(fg.dstSwid))
					continue;
				if(currentbestTunnel == null) {
					currentbestTunnel = tunnel;
				} else if(currentbestTunnel.path.size() > tunnel.path.size()) {
					currentbestTunnel = tunnel;
				}
			}
			if(currentbestTunnel == null) {
				logger.warn("FAILED TO FIND USABLE TUNNEL FOR FG " + fgid);
			} else {
				if(preference.containsKey(currentbestTunnel.id)) {
					preference.get(currentbestTunnel.id).add(fgid);
				} else {
					LinkedList<String> list = new LinkedList<String>();
					list.add(fgid);
					preference.put(currentbestTunnel.id, list);
				}
			}
		}
		
		for(String tid : preference.keySet()) {
			String fgids = "";
			for(String fgid : preference.get(tid)) {
				fgids += fgid + "...";
			}
			logger.info(tid + " is preference for " + fgids);
		}
		return preference;
	}

	private HashMap<String, Long> computeLinkBWallocation(Long id1, Long id2, LinkedList<String> fglist) {
		long avaliableBW;
		HashMap<String, Long> FGBWonLink = new HashMap<String, Long>();

		ConcurrentHashMap<String, Long> currFgDemand = 
				new ConcurrentHashMap<String, Long>();
		
		for(String fgid : fglist) {
			if(getFGCurrDemand(fgid) != 0)
				currFgDemand.put(fgid, getFGCurrDemand(fgid));
		}
		int fgNeedBW = currFgDemand.size();
		//int fgNeedBW = fglist.size();
		if(fgNeedBW == 0) {
			//only happens when all in currFgDemand have demand 0
			return FGBWonLink;//a empty map
		}
		avaliableBW = getLinkCapacity(id1, id2);

		logger.info("STARTING ALLOC ON:" + id1 + "," + id2 + " WITH AVABW" + avaliableBW);
		boolean bwDepleted = true;
		do {
			bwDepleted = true;
			long aveBw = avaliableBW/fgNeedBW;
			avaliableBW = avaliableBW - (aveBw*fgNeedBW);//0 ideally			
			//logger.info("restart : avaBW:" + avaliableBW 
			//		+ " fgNeedBW:" + fgNeedBW + " aveBW:" + aveBw);
			//at this point, assume bw is all allocated to fgs, 
			//then to see how many of them get more than needed
			// and take this part back as available 
			for(String fgid : fglist) {
				if(!currFgDemand.containsKey(fgid))
					continue;
				Long fgdemand = currFgDemand.get(fgid);//getFGCurrDemand(fgid);
				if(fgdemand <= aveBw) {
					logger.info("ON LINK (" + id1 
							+ "<>" + id2 + ") DEMAND MET " 
							+ fgid + " with demand " + fgdemand
							+ " current LinkCap" + getLinkCapacity(id1, id2));
					//demand is met
					fgNeedBW --;
					avaliableBW += (aveBw - fgdemand);
					addFGBW(fgid, currFgDemand.get(fgid));//allFGBW.put(fgid, fgdemand);
					if(FGBWonLink.containsKey(fgid)) {
						FGBWonLink.put(fgid, FGBWonLink.get(fgid) + fgdemand);
					} else 
						FGBWonLink.put(fgid, fgdemand);
					currFgDemand.remove(fgid);
					bwDepleted = false;
				} else {
					logger.info("DEMAND NOT MET (" + id1
							+ "<>" + id2 + ") fg:" 
							+ fgid + " give it " + aveBw
							+ " current LinkCap" + getLinkCapacity(id1, id2));
					currFgDemand.put(fgid, (currFgDemand.get(fgid) - aveBw));
					addFGBW(fgid, aveBw);
					if(FGBWonLink.containsKey(fgid)) {
						FGBWonLink.put(fgid, FGBWonLink.get(fgid) + aveBw);
					} else 
						FGBWonLink.put(fgid, aveBw);
				}
			}
			//at this point bwDepleted remains true means aveBw is still
			//the remainder of the devision
		} while(bwDepleted == false && fgNeedBW > 0);
		
		//should we remove this ????
		if(fgNeedBW > 0) {
			logger.info("making this link full:(" + id1
					+ "->" + id2 + ")");
			markLinkFull(id1, id2);
		}
		return FGBWonLink;
	}

	private boolean addMatchToFG(OFMatch match, String fgid) {
		if(fgMatches.containsKey(fgid)) {
			LinkedList<OFMatch> list = fgMatches.get(fgid);
			if(list.size() == fgCap) {
				return false;
			}
			list.add(match);
			return true;
		} else {
			LinkedList<OFMatch> list = new LinkedList<OFMatch>();
			list.add(match);
			fgMatches.put(fgid, list);
			return true;
		}
	}
	
	private void lookupFgForMatch(OFMatch match) {
		String srcMac = HexString.toHexString(Ethernet.toLong(match.getDataLayerSource()));
		String dstMac = HexString.toHexString(Ethernet.toLong(match.getDataLayerDestination()));
		Long srcSwid = hostSwitchMap.get(srcMac).swid;
		Long dstSwid = hostSwitchMap.get(dstMac).swid;
		logger.info("Looking for MATCH " 
		+ " srcMac:" + srcMac + " dstMac:" + dstMac
		+ " srcSw:" + srcSwid + " dstSw:" + dstSwid);
		
		for(String fgid : allFGs.keySet()){
			FlowGroup fg = allFGs.get(fgid);
			if(fg.srcSwid.equals(srcSwid) && fg.dstSwid.equals(dstSwid)) {
				logger.info("FOUND MATCH src:" + srcSwid + " dst:" + dstSwid);
				if(addMatchToFG(match, fgid) == true) {
					matchFGMap.put(match, fg.id);
					logger.info("set match  " + match + "to " + fgid);
					break;
				}
			}
		}
	}
	
	public boolean readConfigFromFile(String filepath) {
		try {
			JsonFactory jfactory = new JsonFactory();
			ObjectMapper mapper = new ObjectMapper(jfactory);
			//JsonParser jparser = jfactory.createJsonParser(new File(filepath));
			JsonNode root = mapper.readTree(new File(filepath));
			
			Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
			while(fields.hasNext()) {
				Map.Entry<String, JsonNode> field = fields.next();
				String key = field.getKey();
				JsonNode data = field.getValue();
				//logger.info(key + "]]]]]" + data);
				if(key.equals("fg")) {
					Iterator<Map.Entry<String, JsonNode>> fgfields = data.fields();
					while(fgfields.hasNext()) {
						Map.Entry<String, JsonNode> fgfield = fgfields.next();
						String fgkey = fgfield.getKey();
						JsonNode fgdata = fgfield.getValue();
						FlowGroup fg = new FlowGroup();
						fg.id = fgkey;
						fg.srcSwid = Long.parseLong(fgdata.get("src").toString());
						fg.dstSwid = Long.parseLong(fgdata.get("dst").toString());
						fg.demand = Long.parseLong(fgdata.get("dmd").toString());
						allFGs.put(fgkey, fg);
					}
					continue;
				} else if(key.equals("tg")) {
					Iterator<Map.Entry<String, JsonNode>> tgfields = data.fields();
					while(tgfields.hasNext()) {
						Map.Entry<String, JsonNode> tgfield = tgfields.next();
						String tgkey = tgfield.getKey();
						JsonNode tgdata = tgfield.getValue();
						TunnelGroup tg = new TunnelGroup();
						tg.id = tgkey;
						//tg.dstSwid = Long.parseLong(tgdata.get("dst").toString());
						//tg.srcSwid = Long.parseLong(tgdata.get("src").toString());
						//tg.capacity = Long.parseLong(tgdata.get("cap").toString());
						JsonNode ts = tgdata.get("ts");
						JsonNode fgs = tgdata.get("fgs");
						LinkedList<String> list = 
								mapper.readValue(ts.traverse(), new TypeReference<LinkedList<String>>(){});
						
						for(String id : list) {
							tg.allTunnels.add(id);
							TtoTGMap.put(id, tgkey);
						}						
						//logger.info("]]]]]" + tgkey + "]]]]" + tgdata.get("src") + "-->" + tgdata.get("dst"));
						
						allTGs.put(tgkey, tg);
						
						LinkedList<String> list2 =
								mapper.readValue(fgs.traverse(), new TypeReference<LinkedList<String>>(){});
						
						for(String id : list2) {
							tg.currentFGs.add(id);
						}
					}
					continue;
				} else if(key.equals("allts")) {
					Iterator<Map.Entry<String, JsonNode>> allts = data.fields();
					while(allts.hasNext()) {
						Tunnel tunnel = new Tunnel();
						Map.Entry<String, JsonNode> tunnelJson = allts.next();
						String tunnelid = tunnelJson.getKey();
						JsonNode tunneldata = tunnelJson.getValue();
						//logger.info(tunnelid + "-->" + tunneldata);
						LinkedList<String> list = 
								mapper.readValue(tunneldata.traverse(), new TypeReference<LinkedList<String>>(){});
						
						for(String l : list) {
							Long newnode = Long.parseLong(l);
							if(tunnel.path.size() > 0) {
								Long last = tunnel.path.getLast();
								Long lowerid = last > newnode?newnode : last;
								Long higherid = last == lowerid?newnode : last;
								if(linkToTunnelMap.containsKey(lowerid)) {
									ConcurrentHashMap<Long, LinkedList<String>> map = linkToTunnelMap.get(lowerid);
									if(map.containsKey(higherid)) {
										map.get(higherid).add(tunnelid);										
									} else {
										LinkedList<String> tlist = new LinkedList<String>();
										tlist.add(tunnelid);
										map.put(higherid, tlist);
									}
								} else {
									ConcurrentHashMap<Long, LinkedList<String>> map =
											new ConcurrentHashMap<Long, LinkedList<String>>();
									LinkedList<String> tlist = new LinkedList<String>();
									tlist.add(tunnelid);
									map.put(higherid, tlist);
									linkToTunnelMap.put(lowerid, map);									
								}

								logger.info("adding a tunnel to a link:(" + lowerid + "->" + higherid + ") " + tunnelid);
								logger.info("now: (" + lowerid + "->" + higherid + ")" + linkToTunnelMap.get(lowerid).get(higherid).size());
							}
							tunnel.path.add(newnode);
						}
						tunnel.id = tunnelid;
						tunnel.srcSwid = Long.parseLong(list.getFirst());
						tunnel.dstSwid = Long.parseLong(list.getLast());
						Long lowerID = tunnel.srcSwid > tunnel.dstSwid?tunnel.dstSwid : tunnel.srcSwid;
						Long higherID = tunnel.srcSwid == lowerID?tunnel.dstSwid : tunnel.srcSwid;
						if(swidTunnelMap.containsKey(lowerID)) {
							ConcurrentHashMap<Long, LinkedList<String>> map = 
									swidTunnelMap.get(lowerID);
							if(map.containsKey(higherID)) {
								map.get(higherID).add(tunnel.id);
							} else {
								LinkedList<String> tlist = new LinkedList<String>();
								list.add(tunnel.id);
								map.put(higherID, tlist);
							}
						} else {
							ConcurrentHashMap<Long, LinkedList<String>> map = 
									new ConcurrentHashMap<Long, LinkedList<String>>();	
							LinkedList<String> tlist = new LinkedList<String>();
							list.add(tunnel.id);
							map.put(higherID, tlist);
							swidTunnelMap.put(lowerID, map);
						}
						
						allTs.put(tunnelid, tunnel);
					}
					continue;
				} else if (key.equals("linkcap")) {
					linkCap = Long.parseLong(data.toString());
				} else if (key.equals("fgcap")) {
					fgCap = Long.parseLong(data.toString());
				} else {
					logger.warn("Unexcepted Key from config file! key:" + key);
				}
			}

			for(String tid : allTs.keySet()) {
				setTunnelCapacity(tid);
				logger.info("Set tunnel capa to:" + allTs.get(tid).capacity);;
			}
			
			for(Tunnel t : allTs.values())
				t.capacity = linkCap;
			for(TunnelGroup tg : allTGs.values())
				tg.capacity = linkCap;
			logger.info("Config reading finished---------># tunnel:" 
			+ allTs.size()
			+ " # of tgs:" + allTGs.size()
			+ " # of fgs:" + allFGs.size());
			
			return true;
		} catch (JsonParseException e) {
			e.printStackTrace();
			return false;
		} catch (IOException e) {
			e.printStackTrace();
			return false;
		}
	}
	
	private void setTunnelCapacity(String tid) {
		//set capacity of all tunnels, the smallest link capacity is
		//the capacity of this tunnel
		Tunnel tunnel = allTs.get(tid);
		LinkedList<Long> path = tunnel.path;
		Long currCapacity = Long.MAX_VALUE;
		for(int i = 0;i<path.size() - 1;i++) {
			Long swid1 = path.get(i);
			Long swid2 = path.get(i + 1);
			Long bw;
			if(!allSwitchInfo.containsKey(swid1))
				bw = null;
			else 
				bw = allSwitchInfo.get(swid1).getBwByDst(swid2);
			if(bw == null || bw.equals(0))
				continue;
			if(bw < currCapacity) {
				currCapacity = bw;
			}
		}
		tunnel.capacity = currCapacity;		
	}
	
	public void releaseTunnelCapacity(String tid, Long cap) {
		synchronized(allTs) {
			if(allTs.get(tid).capacity + cap > linkCap) {
				logger.info("NOTE tunnel cap exceed maximum!!! on tunnel:" + tid + ":" + allTs.get(tid).capacity + " getting " + cap);
				return;
			}
			allTs.get(tid).capacity += cap;
		}
	}
	
	public boolean consumeTunnelCapacity(String tid, Long cap) {
		synchronized(allTs) {
			if(allTs.get(tid).capacity - cap < 0) {
				logger.info("NOTE tunnel cap not sufficient!!! on tunnel:" + tid + ":" + allTs.get(tid).capacity + " reducing " + cap);
				return false;
			}
			allTs.get(tid).capacity += cap;
		}
		return true;
	}
	
	public Long getTunnelCapacity(String tid) {
		Long cap;
		synchronized(allTs) {
			cap = allTGs.get(tid).capacity;
		}
		return cap;
	}
	
	public LinkedList<TunnelInfo> getTunnelInfoBySrcDst(String srcMAC, String dstMAC) {
		Long srcSwid = getSwitchByMac(srcMAC);
		Long dstSwid = getSwitchByMac(dstMAC);

		logger.info("::::::::::::::::::::::::::>>>>>>>>>" + srcMAC + "on" + srcSwid + "->" + dstMAC + " on " + dstSwid);
		LinkedList<TunnelInfo> tinfolist = new LinkedList<TunnelInfo>();
		for(String fgid : allFGs.keySet()) {
			FlowGroup fg = allFGs.get(fgid);
			if(fg.dstSwid.equals(dstSwid) && fg.srcSwid.equals(srcSwid)) {
				//found the fg!
				if(!FgToTunnelMap.containsKey(fgid)) {
					logger.info("NOTE asking tunnel info based an unassigned FG!" + fgid);
					return null;
				}
				ConcurrentHashMap<String, Long> tmap = FgToTunnelMap.get(fgid);
				for(String tid : tmap.keySet()) {
					TunnelInfo tinfo = new TunnelInfo(tid, allTs.get(tid).path, allTs.get(tid).capacity);
					tinfolist.add(tinfo);
				}
			}
		}
		return tinfolist;
	}
	
	public LinkedList<Long> setMatchToTunnel(Long srcSwid, Long dstSwid, String tid, Long bw, int id) {
		synchronized(matchToTunnelMap) {
			if(matchToTunnelMap.containsKey(srcSwid) && matchToTunnelMap.get(srcSwid).containsKey(dstSwid)) {
				TunnelSwitchListPair pair = matchToTunnelMap.get(srcSwid).get(dstSwid);
				for(Long swid : localControllerSwMap.get(id))
					if(!pair.swids.contains(swid))
						pair.swids.add(swid);
				if(pair.swids.containsAll(allSwitchInfo.keySet())) {
					matchToTunnelMap.get(srcSwid).remove(dstSwid);
					if(matchToTunnelMap.get(srcSwid).size() == 0) {
						matchToTunnelMap.remove(srcSwid);
					}
				}
				logger.info("already exist a path, just use it:conid:" 
				+ id + " src:" + srcSwid + " dst:" + dstSwid + " path:" + allTs.get(pair.tid).path);
				return allTs.get(pair.tid).path;
			}
			TunnelSwitchListPair pair = new TunnelSwitchListPair();
			pair.swids.addAll(localControllerSwMap.get(id));
			pair.tid = tid;
			
			allTs.get(tid).capacity = allTs.get(tid).capacity > bw?allTs.get(tid).capacity - bw:0;
			
			if(matchToTunnelMap.containsKey(srcSwid)) {
				//but not dst
				matchToTunnelMap.get(srcSwid).put(dstSwid, pair);
			} else {
				ConcurrentHashMap<Long, TunnelSwitchListPair> map = new ConcurrentHashMap<Long, TunnelSwitchListPair>();
				map.put(dstSwid, pair);
				matchToTunnelMap.put(srcSwid, map);
			}
			logger.info("notified by local: this tunnel:" + tid + " is picked");
			return null;
		}
	}
	
	//the interesting part!!!!!!!!
	public void computeTGBwAllocation() {
		for(TunnelGroup tg : allTGs.values()) {
			computeTGBWallocation(tg);
			currDescmap = computeFlowInstallation();
			currControllerSwitchFGDesc = generateFGDescForAllController(currDescmap);
		}
	}

	public void setUpMatches() {
		for(OFMatch match : flowByteCount.keySet()) {
			lookupFgForMatch(match);
		}
	}
	
}
