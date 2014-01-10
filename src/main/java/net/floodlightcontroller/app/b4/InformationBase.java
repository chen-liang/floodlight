package net.floodlightcontroller.app.b4;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

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
	ConcurrentHashMap<String, Long> hostSwitchMap; //key mac add, value swid
	ConcurrentHashMap<String,Long> portSwitchMap;
	
	ConcurrentHashMap<Long, SwitchInfo> allSwitchInfo;
	
	ConcurrentHashMap<Integer, CopyOnWriteArrayList<Long>> localControllerSwMap;
	
	ConcurrentHashMap<String, FlowGroup> allFGs; //key is name, only for debugging purpose
	ConcurrentHashMap<String, TunnelGroup> allTGs;
	ConcurrentHashMap<String, Tunnel> allTs;
	ConcurrentHashMap<String, String> TtoTGMap;
	
	ConcurrentHashMap<String, Long> allFGBW; //key is name, value is the BW we give it
	ConcurrentHashMap<String, Long> allTBW;
	
	//stores which link is on which tunnel
	//key is dpid, value is <dpid, tunnelid>
	//NOTE, a link swid1 <-> swid2 needs to be stored twice
	//but to save space, only store once: always use lower 
	//swid as the key to eliminate ambiguity
	ConcurrentHashMap<Long, ConcurrentHashMap<Long, LinkedList<String>>> swidTunnelMap;
	
	Long linkCap;
	
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
			logger.debug("===========>ava ts " + currAvaTunnel.size() 
					+ "==fgs " + currFGsNeedBW.size() + "<===============");
			HashMap<String, LinkedList<String>> preference =
					computeFGpreference(currAvaTunnel, currFGsNeedBW);
			for(String tid : preference.keySet()) {
				LinkedList<String> fgids = 
						preference.get(tid);
				String s = "tid:" + tid;
				for(String fgid : fgids)
					s += "==" + fgid;
				logger.debug("preference:" + s);
				boolean tunnelFull = computeTunnelBWallocation(tid, fgids);
				
				String ss = "currFGBW:";
				for(String fgid : fgids) {
					ss += fgid + "=>" + allFGBW.get(fgid) + ":";
				}
				logger.debug(ss);
				
				if(tunnelFull == true) {
					logger.debug("Tunnel " + tid + " is now full!!!!!!!!!!!!!!!!!!!!!!");
					currAvaTunnel.remove(tid);
					freezeTunnels(tid, currAvaTunnel);
				}
				for(String fgid : preference.get(tid)) {
					if(getFGCurrDemand(fgid) == 0) {
						//------------demand met
						currFGsNeedBW.remove(fgid);
					}
				}
			}
		}
	}
	
	private void freezeTunnels(String tid, LinkedList<String> tlist) {
		Long srcSwid = allTs.get(tid).srcSwid;
		Long dstSwid = allTs.get(tid).dstSwid;
		Long lowerID = srcSwid > dstSwid?dstSwid : srcSwid;
		Long higherID = lowerID == srcSwid?dstSwid : srcSwid;
		if(!swidTunnelMap.containsKey(lowerID) || 
				!swidTunnelMap.get(lowerID).containsKey(higherID))
			return;
		LinkedList<String> peerTunnel = swidTunnelMap.get(lowerID).get(higherID);
		for(String ptid : peerTunnel) {
			if(tlist.contains(ptid)) {
				tlist.remove(ptid);
			}
		}
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
				if(tunnel.srcSwid != fg.srcSwid || tunnel.dstSwid != fg.dstSwid)
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
		return preference;
	}

	private boolean computeTunnelBWallocation(String tunnelid, LinkedList<String> fglist) {

		long avaliableBW;
		boolean tunnelFull = false;
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
					currFgDemand.remove(fgid);
					bwDepleted = false;
				} else {
					logger.debug("DEMAND NOT MET " + fgid + " give it " + aveBw);
					long olddmd = currFgDemand.get(fgid);
					currFgDemand.put(fgid, (olddmd - aveBw));
					addFGBW(fgid, aveBw);
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
			for(String fgid : currFgDemand.keySet()) {
				long grantedBw = getFGCurrDemand(fgid) - currFgDemand.get(fgid);
				addFGBW(fgid, grantedBw);
				allTBW.put(tunnelid, allTBW.get(tunnelid) - grantedBw);
			}
		}
		//logger.debug("tgfg compution finished:" + tg.id + ":" + tg.currentFGs.size());
		return tunnelFull;
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
							tunnel.path.add(Long.parseLong(l));
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
				} else
					logger.warn("Unexcepted Key from config file! key:" + key);
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
