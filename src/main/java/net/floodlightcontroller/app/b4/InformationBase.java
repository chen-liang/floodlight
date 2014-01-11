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

import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.routing.Link;

import org.openflow.protocol.OFMatch;
import org.openflow.util.HexString;
import org.python.antlr.PythonParser.continue_stmt_return;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.org.apache.bcel.internal.generic.ALOAD;

public class InformationBase {

	protected static Logger logger;
	CopyOnWriteArrayList<Long> allSwitches;
	ConcurrentHashMap<Long, CopyOnWriteArrayList<Long>> allSwLinks;
	ConcurrentHashMap<String, Long> hostSwitchMap; //key mac add, value swid
	ConcurrentHashMap<String,Long> portSwitchMap;
	
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
	
	
	//stores which link is on which tunnel
	//key is dpid, value is <dpid, tunnelid>
	//NOTE, a link swid1 <-> swid2 needs to be stored twice
	//but to save space, only store once: always use lower 
	//swid as the key to eliminate ambiguity
	ConcurrentHashMap<Long, ConcurrentHashMap<Long, LinkedList<String>>> swidTunnelMap;
	
	Long linkCap;
	Long fgCap;
	ConcurrentHashMap<String, LinkedList<OFMatch>> fgMatches;
	
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
		hostSwitchMap = new ConcurrentHashMap<String, Long>();
		portSwitchMap = new ConcurrentHashMap<String, Long>();
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
				logger.debug("NOTE: re-setting full link!!!!");
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
		logger.debug("adding byte count:" + match.toString() + " :" + byteCount + "--------");
		lookupFgForMatch(match);
	}
	
	public boolean addHostSwitchMap(String mac, Long swid) {
		hostSwitchMap.put(mac, swid);
		logger.info("base adding mac:" + mac + " is at " + swid);
		return true; //might want to return false later
	}
	
	public boolean addControllerSwMap(Long swid, int id) {
		if(localControllerSwMap.containsKey(id)){
			CopyOnWriteArrayList<Long> swids = localControllerSwMap.get(id);
			swids.add(swid);
		} else {
			CopyOnWriteArrayList<Long> swids = new CopyOnWriteArrayList<Long>();
			swids.add(swid);
			localControllerSwMap.put(id, swids);
		}
		return true;
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
	

	private void addFGtoTunnel(String fgid, String tid, Long bw) {
		logger.debug("allocate fg " + fgid + " to tunnel " + tid + " with bw " + bw);
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
				logger.debug("NOTE allocated BW to fg when it has no tunnel!!!");
				continue;
			}
			String tid = fgTunnelMap.get(fgid);
			Long bw = fgBwMap.get(fgid);
			Tunnel tunnel = allTs.get(tid);
			LinkedList<Long> path = tunnel.path;
			for(int i = 0;i<path.size() - 1;i++) {
				Long id1 = path.get(i);
				Long id2 = path.get(i+1);
				Long currBw = getLinkCapacity(id1, id2);
				if(currBw < bw) {
					logger.debug("NOTE link cap negative!!!!!!!");
				}
				/*
				if(currBw < bw) {
					markLinkFull(id1, id2);
				}*/
				putLinkCapacity(id1, id2, currBw - bw);
				logger.debug("tid:" + tid + "fid:" + fgid + "..>><<update link cap:(" + id1 + "->"
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
		Long lowerid;
		Long higherid;
		LinkedList<String> preference;
		
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
			HashMap<String, LinkedList<String>> preference =
					computeFGpreference(currAvaTunnel, currFGsNeedBW);
			
			HashMap<Long, HashMap<Long, LinkedList<String>>> linkPreference = 
					createLinkPeference(preference);
			
			LinkedList<PreferenceHelper> prflist = sort(linkPreference);
			String s = "";
			for(PreferenceHelper helper : prflist) {
				s += "(" + helper.lowerid + "->" + helper.higherid + ")";
			}
			logger.debug(s);
			
			for(int i = 0;i<prflist.size();i++) {
				
				Long lowerid = prflist.get(i).lowerid;
				Long higherid = prflist.get(i).higherid;
					//go through each link
					//for a link, look at all the fgs that go through this link
					//compute the bw for each of them
					HashMap<String, Long> allocMap = 
							computeLinkBWallocation(lowerid, higherid, linkPreference.get(lowerid).get(higherid));
					////////////////////////////
					logger.debug("for (" + lowerid + "->" + higherid + ")");					
					for(String key : allocMap.keySet()) {
						logger.debug("alloc:" + key + " bw:" + allocMap.get(key));
					}
					
					LinkedList<String> fullTunnels = 
							updateLinkCapByFG(invertPreferenceMap(preference), allocMap);
					for(String fullTunnel : fullTunnels) {
						if(!currAvaTunnel.contains(fullTunnel)) {
							logger.debug("NOTE: removing non-exist tunnel:" + fullTunnel);
							continue;
						}							
						currAvaTunnel.remove(fullTunnel);
						logger.debug("remove full tunnel " + fullTunnel + " now have " + currAvaTunnel);
					}
					//I think this safe, because preference is sorted on demand,
					//it could happen that some links are congested making other
					//links on the same tunnel useless. In this case, no need to
					//allocate bw
					if(currAvaTunnel.size() == 0) {
						logger.debug("NO MORE TUNNEL AVALIABLE, BREAK==>");
						break;
					}
					/*if(fullLinks.containsKey(lowerid) && 
							fullLinks.get(lowerid).contains(higherid)) {
						//this link is full!!
						if(!linkToTunnelMap.containsKey(lowerid)) {
							logger.debug("NOTE!!!!!!!link not recognized lowerid:" + lowerid);
						} else if (!linkToTunnelMap.get(lowerid).containsKey(higherid)){
							logger.debug("NOTE!!!!!!!" + lowerid + "link not recognized" + higherid);
						}
						LinkedList<String> tids = linkToTunnelMap.get(lowerid).get(higherid);
						for(String tid : tids) {
							//remove all tunnels that use this link
							currAvaTunnel.remove(tid);
						}
					}*/
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
		}		
		logger.debug("FG bw allocation finished!!");
		for(String fgid : allFGBW.keySet()) {
			logger.debug(fgid + "->" + allFGBW.get(fgid));
		}
		logger.debug("Full links:");
		for(Long lowerid : fullLinks.keySet()) {
			CopyOnWriteArrayList<Long> dstid = fullLinks.get(lowerid);
			logger.debug("(" + lowerid + "," + dstid + ")");
		}
	}
	
	private HashMap<Long, HashMap<Long, LinkedList<String>>>
	createLinkPeference(
			HashMap<String, LinkedList<String>> tunnelPreference) {
		
		HashMap<Long, HashMap<Long, LinkedList<String>>> linkPreference = 
				new HashMap<Long, HashMap<Long,LinkedList<String>>>();
		
		for(Long lowerid : linkToTunnelMap.keySet()) {
			ConcurrentHashMap<Long, LinkedList<String>> linkdstMap = linkToTunnelMap.get(lowerid);
			
			for(Long higherid : linkdstMap.keySet()) {
				LinkedList<String> tunnellist = linkToTunnelMap.get(lowerid).get(higherid);
				
				for(String tunnelid : tunnellist) {
					if(!tunnelPreference.containsKey(tunnelid)) //no fg interested in this tunnel, skip it
						continue;
					LinkedList<String> fgPreferThisTunnel = tunnelPreference.get(tunnelid);
					if(linkPreference.containsKey(lowerid)) {
						if(linkPreference.get(lowerid).containsKey(higherid)) {
							LinkedList<String> previousFGs = linkPreference.get(lowerid).get(higherid);
							for(String s : fgPreferThisTunnel) {
								logger.debug("adding " + s + " to " + lowerid + "->" + higherid);
								previousFGs.add(s);
							}
							linkPreference.get(lowerid).put(higherid, previousFGs);
						} else {
							LinkedList<String> newFGs = new LinkedList<String>();
							for(String s : fgPreferThisTunnel) {
								newFGs.add(s);
							}
							linkPreference.get(lowerid).put(higherid, newFGs);
						}
					} else {
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
				String s = "<><><>><><><><" + lowerid + "><><>" + higherid + " "
						+ (linkPreference.get(lowerid).get(higherid) == null?"NULLLLLL":linkPreference.get(lowerid).get(higherid).size());
				s += " while " + linkToTunnelMap.get(lowerid).get(higherid).size();
				logger.debug(s);
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
	//NOTE assumes all ts in tunnels are available  
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
			logger.debug(tid + " is preference for " + fgids);
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
		boolean bwDepleted = true;
		do {
			bwDepleted = true;
			long aveBw = avaliableBW/fgNeedBW;
			avaliableBW = avaliableBW - (aveBw*fgNeedBW);//0 ideally			
			//logger.debug("restart : avaBW:" + avaliableBW 
			//		+ " fgNeedBW:" + fgNeedBW + " aveBW:" + aveBw);
			//at this point, assume bw is all allocated to fgs, 
			//then to see how many of them get more than needed
			// and take this part back as available 
			for(String fgid : fglist) {
				if(!currFgDemand.containsKey(fgid))
					continue;
				Long fgdemand = currFgDemand.get(fgid);//getFGCurrDemand(fgid);
				if(fgdemand <= aveBw) {
					logger.debug("ON LINK (" + id1 
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
					logger.debug("DEMAND NOT MET (" + id1
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
		
		/*if(fgNeedBW > 0) {
			logger.debug("making this link full:(" + id1
					+ "->" + id2 + ")");
			markLinkFull(id1, id2);
		}*/
		return FGBWonLink;
	}

	/*private HashMap<String, Long> computeTunnelBWallocation(String tunnelid, LinkedList<String> fglist) {

		long avaliableBW;
		boolean tunnelFull = false;
		HashMap<String, Long> FGBWonTunnel = new HashMap<String, Long>();
		
		if(!allTBW.containsKey(tunnelid)) {
			avaliableBW = allTs.get(tunnelid).capacity;
			allTBW.put(tunnelid, avaliableBW);
		} else {
			avaliableBW = allTBW.get(tunnelid);
		}

		int fgNeedBW = fglist.size();
		ConcurrentHashMap<String, Long> currFgDemand = 
				new ConcurrentHashMap<String, Long>();

		for(String fgid : fglist) {
			currFgDemand.put(fgid, getFGCurrDemand(fgid));
		}

		boolean bwDepleted = true;
		do {

			bwDepleted = true;
			long aveBw = avaliableBW/fgNeedBW;
			avaliableBW = avaliableBW - (aveBw*fgNeedBW);//0 ideally			
			//logger.debug("restart : avaBW:" + avaliableBW 
			//		+ " fgNeedBW:" + fgNeedBW + " aveBW:" + aveBw);
			//at this point, assume bw is all allocated to fgs, 
			//then to see how many of them get more than needed
			// and take this part back as available 
			for(String fgid : fglist) {
				if(!currFgDemand.containsKey(fgid))
					continue;
				Long fgdemand = currFgDemand.get(fgid);//getFGCurrDemand(fgid);
				if(fgdemand <= aveBw) {
					logger.debug("DEMAND MET " + fgid + " with demand " + fgdemand);
					//demand is met
					fgNeedBW --;
					avaliableBW += (aveBw - fgdemand);
					addFGBW(fgid, fgdemand);//allFGBW.put(fgid, fgdemand);
					allTBW.put(tunnelid, allTBW.get(tunnelid) - fgdemand);
					FGBWonTunnel.put(fgid, fgdemand);
					
					currFgDemand.remove(fgid);
					bwDepleted = false;
				} else {
					logger.debug("DEMAND NOT MET " + fgid + " give it " + aveBw);
					currFgDemand.put(fgid, (currFgDemand.get(fgid) - aveBw));
					addFGBW(fgid, aveBw);
					allTBW.put(tunnelid, allTBW.get(tunnelid) - aveBw);
					if(FGBWonTunnel.containsKey(fgid)) {
						FGBWonTunnel.put(fgid, FGBWonTunnel.get(fgid) + aveBw);
					} else 
						FGBWonTunnel.put(fgid, aveBw);
				}
			}
			//at this point bwDepleted remains true means aveBw is still
			//the remainder of the devision
		} while(bwDepleted == false && fgNeedBW > 0);

		if(fgNeedBW > 0) {
			//some fg still can not be satisfied
			//NOTE::::: FG NOT SATISFIED BECAUSE TUNNEL DO NOT HAVE MUCH BW!
			//MAKE THIS TUNNEL AS UNAVALIABLE!!!
			tunnelFull = true;
//			for(String fgid : currFgDemand.keySet()) {
//				long grantedBw = getFGCurrDemand(fgid) - currFgDemand.get(fgid);
//				addFGBW(fgid, grantedBw);
//				allTBW.put(tunnelid, allTBW.get(tunnelid) - grantedBw);
//				
//			}
		}
		//logger.debug("tgfg compution finished:" + tg.id + ":" + tg.currentFGs.size());
		if(tunnelFull == true)
			fullTunnels.add(tunnelid);
		return FGBWonTunnel;
	}*/


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
		Long srcSwid = hostSwitchMap.get(srcMac);
		Long dstSwid = hostSwitchMap.get(dstMac);
		logger.debug("Looking for MATCH " 
		+ " srcMac:" + srcMac + " dstMac:" + dstMac
		+ " srcSw:" + srcSwid + " dstSw:" + dstSwid);
		
		for(String fgid : allFGs.keySet()){
			FlowGroup fg = allFGs.get(fgid);
			if(fg.srcSwid.equals(srcSwid) && fg.dstSwid.equals(dstSwid)) {
				logger.debug("FOUND MATCH src:" + srcSwid + " dst:" + dstSwid);
				if(addMatchToFG(match, fgid) == true) {
					matchFGMap.put(match, fg.id);
					logger.debug("set match  " + match + "to " + fgid);
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
				//logger.debug(key + "]]]]]" + data);
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
						//logger.debug("]]]]]" + tgkey + "]]]]" + tgdata.get("src") + "-->" + tgdata.get("dst"));
						
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
						//logger.debug(tunnelid + "-->" + tunneldata);
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

								logger.debug("adding a tunnel to a link:(" + lowerid + "->" + higherid + ") " + tunnelid);
								logger.debug("now: (" + lowerid + "->" + higherid + ")" + linkToTunnelMap.get(lowerid).get(higherid).size());
							}
							tunnel.path.add(newnode);
						}
						tunnel.id = tunnelid;
						tunnel.srcSwid = Long.parseLong(list.getFirst());
						tunnel.dstSwid = Long.parseLong(list.getLast());
						Long lowerID = tunnel.srcSwid > tunnel.dstSwid?tunnel.dstSwid : tunnel.srcSwid;
						Long higherID = tunnel.srcSwid == lowerID?tunnel.dstSwid : tunnel.srcSwid;
						if(swidTunnelMap.contains(lowerID)) {
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

			for(Tunnel t : allTs.values())
				t.capacity = linkCap;
			for(TunnelGroup tg : allTGs.values())
				tg.capacity = linkCap;
			logger.debug("Config reading finished---------># tunnel:" 
			+ allTs.size()
			+ " # of tgs:" + allTGs.size()
			+ " # of fgs:" + allFGs.size());
			
			for(TunnelGroup tg : allTGs.values())
				computeTGBWallocation(tg);
			
			for(OFMatch match : flowByteCount.keySet()) {
				lookupFgForMatch(match);
			}
			
			return true;
		} catch (JsonParseException e) {
			e.printStackTrace();
			return false;
		} catch (IOException e) {
			e.printStackTrace();
			return false;
		}
	}
}
