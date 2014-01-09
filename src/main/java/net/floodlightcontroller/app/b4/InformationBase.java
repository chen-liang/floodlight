package net.floodlightcontroller.app.b4;

import java.io.File;
import java.io.IOException;
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
	
	ConcurrentHashMap<String, Long> allFGBW; //key is name, value is the BW we give it
	
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
		Long srcSwid;
		Long dstSwid;
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
	
	protected void addFGtoTG() {
		for(FlowGroup fg : allFGs.values()) {
			for(TunnelGroup tg : allTGs.values()) {
				if(tg.srcSwid == fg.srcSwid && tg.dstSwid == fg.dstSwid) {
					logger.info("adding a fg to tg, where src is " + fg.srcSwid + " dst is " + fg.dstSwid);
					tg.currentFGs.add(fg.id);
				}
			}
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
	
	
	public void computeFGBW() {
		for(TunnelGroup tg : allTGs.values()) {
			logger.debug("tgfg compution start:" + tg.id + ":" + tg.currentFGs.size());
			
			long avaliableBW = tg.capacity;
			int fgNeedBW = tg.currentFGs.size();
			ConcurrentHashMap<String, Long> currFgDemand = 
					new ConcurrentHashMap<String, Long>();
			
			for(FlowGroup fg : allFGs.values()) {
				currFgDemand.put(fg.id, fg.demand);
			}
			
			boolean bwDepleted = true;
			do {
				long aveBw = avaliableBW/fgNeedBW;
				avaliableBW = avaliableBW - (aveBw*fgNeedBW);//0 ideally
				//at this point, assume bw is all allocated to fgs, 
				//then to see how many of them get more than needed
				// and take this part back as available 
				for(String fgid : tg.currentFGs) {
					FlowGroup fg = allFGs.get(fgid);
					if(!currFgDemand.containsKey(fg.id))
						continue;
					if(currFgDemand.get(fg.id) <= aveBw) {
						//demand is met
						fgNeedBW --;
						avaliableBW += (aveBw - fg.demand);
						allFGBW.put(fg.id, fg.demand);
						currFgDemand.remove(fg.id);
						bwDepleted = false;
					} else {
						long olddmd = currFgDemand.get(fg.id);
						currFgDemand.put(fg.id, (olddmd - aveBw));
					}
				}
				//at this point bwDepleted remains true means aveBw is still
				//the remainder of the devision
			} while(bwDepleted == false && fgNeedBW > 0);
			
			if(fgNeedBW > 0) {
				//some fg still can not be satisfied
				for(String key : currFgDemand.keySet()) {
					long grantedBw = allFGs.get(key).demand - currFgDemand.get(key);
					allFGBW.put(key, grantedBw);
				}
			}
			
			logger.debug("tgfg compution finished:" + tg.id + ":" + tg.currentFGs.size());
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
						//logger.debug("]]]]]" + fgkey + "]]]]" + fgdata.get("src") + "-->" + fgdata.get("dst"));
						logger.debug("adding new fg:" + fg.id 
								+ " src:" + fg.srcSwid 
								+ " dst:" + fg.dstSwid 
								+ " with dmd:" + fg.demand);
						allFGs.put(fgkey, fg);
					}
					continue;
				}
				
				if(key.equals("tg")) {
					Iterator<Map.Entry<String, JsonNode>> tgfields = data.fields();
					while(tgfields.hasNext()) {
						Map.Entry<String, JsonNode> tgfield = tgfields.next();
						String tgkey = tgfield.getKey();
						JsonNode tgdata = tgfield.getValue();
						TunnelGroup tg = new TunnelGroup();
						tg.id = tgkey;
						tg.dstSwid = Long.parseLong(tgdata.get("dst").toString());
						tg.srcSwid = Long.parseLong(tgdata.get("src").toString());
						tg.capacity = Long.parseLong(tgdata.get("cap").toString());
						JsonNode array = tgdata.get("ts");
						LinkedList<String> list = 
								mapper.readValue(array.traverse(), new TypeReference<LinkedList<String>>(){});
						
						String allids = "";
						for(String id : list) {
							tg.allTunnels.add(id);
							allids += "->" + id;
						}						
						//logger.debug("]]]]]" + tgkey + "]]]]" + tgdata.get("src") + "-->" + tgdata.get("dst"));
						logger.debug("adding new tg:" + tg.id 
								+ " src:" + tg.srcSwid 
								+ " dst:" + tg.dstSwid
								+ " cap:" + tg.capacity
								+ " tunnels:" + allids);
						allTGs.put(tgkey, tg);
					}
					continue;
				}
				
				if(key.equals("allts")) {
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
						logger.info("created new tunnel:" + tunnel);
						allTs.put(tunnelid, tunnel);
					}
					continue;
				}
				logger.warn("Unexcepted Key from config file! key:" + key);
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
