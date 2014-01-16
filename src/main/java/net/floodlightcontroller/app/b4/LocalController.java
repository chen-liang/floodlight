package net.floodlightcontroller.app.b4;

import java.io.IOException;
import java.rmi.AlreadyBoundException;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.openflow.protocol.OFFlowMod;
import org.openflow.protocol.OFMatch;
import org.openflow.protocol.OFMessage;
import org.openflow.protocol.OFPacketIn;
import org.openflow.protocol.OFPacketOut;
import org.openflow.protocol.OFPort;
import org.openflow.protocol.OFStatisticsRequest;
import org.openflow.protocol.OFType;
import org.openflow.protocol.OFPacketIn.OFPacketInReason;
import org.openflow.protocol.action.OFAction;
import org.openflow.protocol.action.OFActionOutput;
import org.openflow.protocol.statistics.OFFlowStatisticsReply;
import org.openflow.protocol.statistics.OFFlowStatisticsRequest;
import org.openflow.protocol.statistics.OFStatistics;
import org.openflow.protocol.statistics.OFStatisticsType;

import net.floodlightcontroller.app.b4.rmi.FlowStatsDesc;
import net.floodlightcontroller.app.b4.rmi.RemoteGlobalConstant;
import net.floodlightcontroller.app.b4.rmi.RemoteGlobalServer;
import net.floodlightcontroller.app.b4.rmi.SwitchFlowGroupDesc;
import net.floodlightcontroller.app.b4.rmi.TunnelInfo;
import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.IOFMessageListener;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.IOFSwitch.PortChangeType;
import net.floodlightcontroller.core.IOFSwitchListener;
import net.floodlightcontroller.core.ImmutablePort;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.core.types.MacVlanPair;
import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.counter.ICounterStoreService;
import net.floodlightcontroller.devicemanager.IDeviceService;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import net.floodlightcontroller.learningswitch.LearningSwitch;
import net.floodlightcontroller.linkdiscovery.ILinkDiscoveryListener;
import net.floodlightcontroller.linkdiscovery.ILinkDiscoveryService;
import net.floodlightcontroller.packet.BSN;
import net.floodlightcontroller.packet.Data;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPacket;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.packet.UDP;
import net.floodlightcontroller.routing.IRoutingService;
import net.floodlightcontroller.topology.ITopologyService;

import org.openflow.util.HexString;
import org.openflow.util.LRULinkedHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LocalController implements IOFMessageListener, IFloodlightModule, ILinkDiscoveryListener, 
IOFSwitchListener {

	protected IFloodlightProviderService floodlightProvider;
	protected ILinkDiscoveryService linkDiscoverer;
	protected IDeviceService deviceManager;
    protected ICounterStoreService counterStore;


    protected ConcurrentHashMap<Long, LinkedList<OFMatch>> matchesWeHaveSeen;
    
	protected ConcurrentSkipListSet<Long> macAddresses;
	protected ConcurrentHashMap<Long, LinkedList<ImmutablePort>> swLocalCache;
	protected static Logger logger;
	protected Map<String, String> configParams;

	protected boolean remote;
	RemoteGlobalServer server;

	protected Thread worker;

	protected LocalHandler handler;//a descriptor of self	

	int rmi_serverport;
	String rmi_serverhost;

	//may want to use a match -> OFFlowStatisticsReply map
	//instead just a number indicating number of byte?
	protected ConcurrentHashMap<OFMatch, Long> flowStatByMatch;
	
	HashMap<Long, LinkedList<SwitchFlowGroupDesc>> currSwFgmap;

	protected ConcurrentSkipListSet<OFMatch> matchesWeCovered; //do not cover the same match in the same config interval
	/////////////////////////////////////////
	// Stores the learned state for each switch
	protected Map<IOFSwitch, Map<MacVlanPair,Short>> macVlanToSwitchPortMap;
    // more flow-mod defaults
    protected static short FLOWMOD_DEFAULT_IDLE_TIMEOUT = 5; // in seconds
    protected static short FLOWMOD_DEFAULT_HARD_TIMEOUT = 0; // infinite
    protected static short FLOWMOD_PRIORITY = 100;
    // for managing our map sizes
    protected static final int MAX_MACS_PER_SWITCH  = 1000;
    // normally, setup reverse flow as well. Disable only for using cbench for comparison with NOX etc.
    protected static final boolean LEARNING_SWITCH_REVERSE_FLOW = true;
	/////////////////////////////////////////
    protected static short CONFIG_INTERVAL = 3;
    
    protected ConcurrentSkipListSet<String> macGlobalAlreadyKnow;//NOTE ELEMENT NEED TO BE REMOVED SOMETIME
    
    protected HashMap<String, HashMap<String,FlowStatsDesc>> 
    computeFlowDemand(LinkedList<OFStatistics> values) {
    	if(values.size() == 0) 
    		return null;    	
    	//whether makes this a member variable? by doing so we can record all histories, but do we want to do that?
    	HashMap<String, HashMap<String,FlowStatsDesc>> map = new HashMap<String, HashMap<String,FlowStatsDesc>>();
    	for(OFStatistics value : values) {
    		if(!(value instanceof OFFlowStatisticsReply)) {
    			logger.info("+++++++++++++++=====NOTE:unexcepted states Type!!!" + value.getClass().getCanonicalName());
    			continue;
    		}
    		OFFlowStatisticsReply fstats = (OFFlowStatisticsReply)value;
    		OFMatch match = fstats.getMatch();
    		//Long sourceMac = Ethernet.toLong(match.getDataLayerSource());
    		//Long destMac = Ethernet.toLong(match.getDataLayerDestination());
    		String sourceMac = HexString.toHexString(match.getDataLayerSource());
    		String destMac = HexString.toHexString(match.getDataLayerDestination());
    		HashMap<String, FlowStatsDesc> destMap;
    		if(map.containsKey(sourceMac)) {
    			destMap = map.get(sourceMac);
    		} else {
    			destMap = new HashMap<String, FlowStatsDesc>();
    		}
    		FlowStatsDesc desc = new FlowStatsDesc(fstats.getByteCount(), match);
    		
    		destMap.put(destMac, desc);
    		logger.info("--------^^^^^^^^^^^adding flow count:" + sourceMac + "->" + destMac + ":" + fstats.getByteCount());
    		map.put(sourceMac, destMap);
    	}    	
    	return map;
    }
    
	class workerThread implements Runnable {
		@Override
		public void run() {
			while(true) {
				//one job is to send out states request periodically
				//logger.info("**********************querying for stats!!!" + swLocalCache.size());
				for(Long swid : swLocalCache.keySet()) {
					IOFSwitch sw = floodlightProvider.getSwitch(swid);
					LinkedList<OFStatistics> values = new LinkedList<OFStatistics>(getStats(sw));
					if(values.size() > 0) {
						for(OFStatistics stat : values) {
							if(stat instanceof OFFlowStatisticsReply) {
							OFFlowStatisticsReply flowstat = (OFFlowStatisticsReply)stat;
							flowStatByMatch.put(flowstat.getMatch(), flowstat.getByteCount());
							} else {
								logger.info("NOTE Unexceptioned Stat " + stat.getClass().getCanonicalName());
							}
						}
						try {
							server.sendFlowDemand(computeFlowDemand(values), handler.id);
						} catch (RemoteException e) {
							e.printStackTrace();
						}

						//create map where key = match, value = list of desc for this match
						//HashMap<OFMatch, LinkedList<SwitchFlowGroupDesc>> matchDescmap = matchMatchesToFgAllocations(flowStatByMatch);

						//create flows given the desc map!
						//createFlowForMatchByMatchDescMap(matchDescmap);
						
						//matchesWeHaveSeen.clear();
						findTunnelAndInstall(flowStatByMatch);
						

					} else {
						//logger.info("***************************" + swid);
					}
					sendPortMacToAll(swid);
				}
				try {
					Thread.sleep(2000);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
		}		
	}
	
	/*private OFMatch matchWithoutInport(OFMatch match) {
		//generate a clone of match, only difference is that, the inport
		//is reset
		//why need this? say, a match goes through sw1 and sw2. In fact this
		//is the same match, but they will trigger method calls separately
		//because they have different inport! no need to do that
		return match.clone().setInputPort(Short.MAX_VALUE);
	}*/
	
	//for a switch on each port, send out a empty packet
	//whose src address is the mac address of the port
	//this special match will be reported to global controller
	//therefore controller can know the connectivity of switches
	//across local controllers
	private static final byte[] FAKE_DST =
            HexString.fromHexString("ee:ee:ee:ee:ee:ee");
	private final short SIGNAL_VLANID = Short.MAX_VALUE;
	private String FAKE_IP_SRC = "255.255.255.255";
	private int FAKE_IP_DST = Integer.MAX_VALUE;
	
	private void sendPortMacToAll(Long swid) {
		if(!swLocalCache.containsKey(swid)) {
			logger.debug("NOTE send port to peer, but don't know this swid!" + swid);
			return;
		}
		LinkedList<ImmutablePort> ports = swLocalCache.get(swid);
		for(ImmutablePort port : ports) {
			
			IPacket ip = new IPv4()
            .setTtl((byte) 128)
            .setSourceAddress(FAKE_IP_SRC)
            .setDestinationAddress(FAKE_IP_DST)
            .setPayload(new UDP()
            .setSourcePort((short) 5000)
            .setDestinationPort((short) 5001)
            .setPayload(new Data(new byte[] {0x01})));

			Ethernet ethernet;
			ethernet = (Ethernet) new Ethernet().setSourceMACAddress(port.getHardwareAddress())
					.setDestinationMACAddress(FAKE_DST)
					.setEtherType(Ethernet.TYPE_IPv4)
					.setVlanID(SIGNAL_VLANID).setPayload(ip);

	        // serialize and wrap in a packet out
	        byte[] data = ethernet.serialize();
			OFPacketOut po = (OFPacketOut) floodlightProvider.getOFMessageFactory()
					.getMessage(OFType.PACKET_OUT);
			po.setBufferId(OFPacketOut.BUFFER_ID_NONE);
			po.setInPort(OFPort.OFPP_NONE);
			// set data and data length
			po.setLengthU(OFPacketOut.MINIMUM_LENGTH + data.length);
			po.setPacketData(data);

			// set actions
			List<OFAction> actions = new ArrayList<OFAction>(1);
			OFAction action = new OFActionOutput(port.getPortNumber(), (short) 0);
			actions.add(action);
			po.setActions(actions);
			short  actionLength = action.getLength();
	        po.setActionsLength(actionLength);
	        po.setLengthU(po.getLengthU() + po.getActionsLength());

			IOFSwitch sw = floodlightProvider.getSwitch(swid);
			try {
				logger.info("****************Writing this info " + po + " on " + swid + " p " + port.getName());
				sw.write(po, null);
				sw.flush();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}
	
	private void findTunnelAndInstall(ConcurrentHashMap<OFMatch, Long> matches) {
		//NOTE this only works when we assume all src -> dst
		//flows belong to the same tunnel
		ConcurrentSkipListSet<String> tunnelCovered = 
				new ConcurrentSkipListSet<String>();
		for(OFMatch match : matches.keySet()) {
			logger.info("now for this match:" + match);
			TunnelInfo tinfo = getSwitchInstallByMatch(match);
			if(tinfo == null) {
				logger.info("no tunnel info! for this:" + match);
				continue;
			} else {
				logger.info("............" + tinfo.getPath());
			}
			if(tunnelCovered.contains(tinfo.getTid())) {
				logger.info("already installed for this tunnel:" + tinfo.getTid());
				continue;
			}
			pushFlowToSwitches(tinfo.getPath(), match, true);
		}
	}
	
    private List<OFStatistics> getStats(IOFSwitch sw) {
    	List<OFStatistics> values = null;
    	
    	OFStatisticsRequest req = new OFStatisticsRequest();
    	req.setStatisticType(OFStatisticsType.FLOW);
    	int reqLen = req.getLengthU();
    	OFFlowStatisticsRequest specificReq = new OFFlowStatisticsRequest();
    	specificReq.setMatch(new OFMatch().setWildcards(0xffffffff));
    	specificReq.setOutPort(OFPort.OFPP_NONE.getValue());
    	specificReq.setTableId((byte)0xff);
    	req.setStatistics(Collections.singletonList((OFStatistics)specificReq));
    	reqLen += specificReq.getLength();
    	req.setLengthU(reqLen);
    	try {
			Future<List<OFStatistics>> future = sw.queryStatistics(req);
			values = future.get(10, TimeUnit.SECONDS);
		} catch (IOException e) {
			e.printStackTrace();
		} catch (InterruptedException e) {
			e.printStackTrace();
		} catch (ExecutionException e) {
			e.printStackTrace();
		} catch (TimeoutException e) {
			e.printStackTrace();
		}
    	return values;
    }

	@Override
	public String getName() {    
		return LocalController.class.getSimpleName();
	}

	@Override
	public boolean isCallbackOrderingPrereq(OFType type, String name) {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean isCallbackOrderingPostreq(OFType type, String name) {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleServices() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Map<Class<? extends IFloodlightService>, IFloodlightService> getServiceImpls() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
		Collection<Class<? extends IFloodlightService>> l =
				new ArrayList<Class<? extends IFloodlightService>>();
		l.add(IFloodlightProviderService.class);
		l.add(ILinkDiscoveryService.class);
		l.add(IDeviceService.class);
		l.add(IRoutingService.class);
		l.add(ITopologyService.class);
		l.add(ICounterStoreService.class);
		return l;
	}

	@Override
	public void init(FloodlightModuleContext context)
			throws FloodlightModuleException {  
		matchesWeHaveSeen = new ConcurrentHashMap<Long, LinkedList<OFMatch>>();
		macGlobalAlreadyKnow = new ConcurrentSkipListSet<String>();
		macVlanToSwitchPortMap =
				new ConcurrentHashMap<IOFSwitch, Map<MacVlanPair,Short>>();
		flowStatByMatch = new ConcurrentHashMap<OFMatch, Long>();
		floodlightProvider = context.getServiceImpl(IFloodlightProviderService.class);
		floodlightProvider.addOFSwitchListener(this);
		linkDiscoverer = context.getServiceImpl(ILinkDiscoveryService.class);
		linkDiscoverer.addListener(this);
		deviceManager = context.getServiceImpl(IDeviceService.class);
		macAddresses = new ConcurrentSkipListSet<Long>();
		swLocalCache = new ConcurrentHashMap<Long, LinkedList<ImmutablePort>>();
		logger = LoggerFactory.getLogger(LocalController.class);
        counterStore =
                context.getServiceImpl(ICounterStoreService.class);
		configParams = context.getConfigParams(this);
		String global = configParams.get("global");
		logger.info("---------------remote:" + global);
		if(global != null && global.equals("enabled")) {
			remote = true;
		} else {
			remote = false;
		}
	}

	public LocalHandler getHandler() {
		return handler;
	}


	protected void getServerConfig() {
		if(configParams.get("globalrmiport") == null) {
			rmi_serverport = RemoteGlobalConstant.RMI_PORT;
			logger.info("@@@@@@@@@@@@NOTE:global rmi port not speficied, use default " + rmi_serverport);
		} else {
			rmi_serverport = Integer.parseInt(configParams.get("globalrmiport"));
			logger.info("@@@@@@@@@@@@NOTE:global rmi port specified to " + rmi_serverport);
		}

		if(configParams.get("globalrmiserver") == null) {			
			rmi_serverhost = RemoteGlobalConstant.RMI_HOST;
			logger.info("@@@@@@@@@@@@NOTE:global rmi host not speficied, use default " + rmi_serverhost);
		} else {
			rmi_serverhost = configParams.get("globalrmiserver");
			logger.info("@@@@@@@@@@@@NOTE:global rmi host specified to " + rmi_serverhost);
		}
	}

	@Override
	public void startUp(FloodlightModuleContext context) {
		floodlightProvider.addOFMessageListener(OFType.PACKET_IN, this);
		worker = new Thread(new workerThread());
		worker.start();
		if(remote == true) {
			logger.info("====>connecting to global enabled!!");
			getServerConfig();
			try {
				Registry registry = LocateRegistry.getRegistry(rmi_serverhost, 
						rmi_serverport);
				server = (RemoteGlobalServer) registry.lookup(RemoteGlobalConstant.GLOBAL_ID);
				LocalHandler lhandler = server.contact();
				handler = lhandler;
				Registry localregistry = LocateRegistry.createRegistry(handler.portToUse);
				LocalRMIImpl impl = new LocalRMIImpl(this);
				localregistry.bind(handler.name, impl);
				logger.info("self-reigstered to global! Got id:" + handler.id);
			} catch (RemoteException e) {
				e.printStackTrace();
			} catch (NotBoundException e) {
				e.printStackTrace();
			} catch (AlreadyBoundException e) {
				e.printStackTrace();
			}
		} else {
			logger.info("====>connecting to global disabled!!");
		}
	}

	@Override
	public Command receive(IOFSwitch sw, OFMessage msg, FloodlightContext cntx) {
		Ethernet eth =
				IFloodlightProviderService.bcStore.get(cntx,
						IFloodlightProviderService.CONTEXT_PI_PAYLOAD);

		Long sourceMACHash = Ethernet.toLong(eth.getSourceMACAddress());
		
		if (!macAddresses.contains(sourceMACHash)) {
			macAddresses.add(sourceMACHash);
			String macadd = HexString.toHexString(sourceMACHash);
			//CAUTION!!! SEND THIS INFO TO GLOBAL IMMIDIATELY UPON SEEING IT, OTHERWISE CONFUSTION ON GLOBAL WILL HAPPEN!
			//AND!!! EVEN THIS ASSUMES MININET HAS --MAC OPTION
			String s = "";
			for(ImmutablePort port : sw.getPorts()) {
				s += port.getName() + " ";
			}
			logger.info("mac in long " + sourceMACHash + " MAC Address: " + macadd + " seen on switch: " + sw.getId());
			
			try {
				
				if(msg.getType() == OFType.PACKET_IN) {
					OFPacketIn pi = (OFPacketIn)msg;
					s += "{{{{{--->" + pi.getInPort();
					server.addHostSwitchMap(macadd, sw.getId(), pi.getInPort());
				} else {
					logger.info("NOTE Unexcepted mag type " + msg.getType() + " from " + sw.getId());
				}
				logger.info("the ports:" + s);
			} catch (RemoteException e) {
				e.printStackTrace();
			}
		}

		Command c = Command.CONTINUE;
		if(msg.getType() == OFType.PACKET_IN) {
			OFPacketIn pi = (OFPacketIn)msg;
			OFMatch pmatch = new OFMatch();
			pmatch.loadFromPacket(pi.getPacketData(), pi.getInPort());
			logger.info("on" + sw.getId() + " seeing a match:" + pmatch);
			Long dstLong = Ethernet.toLong(pmatch.getDataLayerDestination());
			if(dstLong.equals(Ethernet.toLong(FAKE_DST))) {
				String s = "***********************************************SEE THIS!!!! vlanid" 
			+ eth.getVlanID() + " and the src!!!!:" + HexString.toHexString(sourceMACHash);
				logger.info(s);
				//src of this packet is actually a port! tell global!!!
				try {
					String peerPortMac = HexString.toHexString(dstLong);
					boolean notknown = true; //assume global does not know this info
					notknown = server.portMacNoted(peerPortMac, sw.getId(), pi.getInPort());
					if(notknown == false) {
						//global already know that, don't bother anymore
						macGlobalAlreadyKnow.add(peerPortMac);
					}
				} catch (RemoteException e) {
					e.printStackTrace();
				}
			} else if(!matchesWeHaveSeen.containsKey(sw.getId()) || 
					!matchesWeHaveSeen.get(sw.getId()).contains(pmatch)) {
				logger.info("Never seen this match, try flood it first, then try create using default");
				//c = this.processPacketInMessage(sw, (OFPacketIn)msg, cntx);
				//writePacketOutForPacketIn(sw, pi, OFPort.OFPP_FLOOD.getValue());
				ConcurrentHashMap<OFMatch, Long> match = new ConcurrentHashMap<OFMatch, Long>();
				match.put(pmatch, new Long(0));
				findTunnelAndInstall(match);
			}
		}
		return c;
	}

	@Override
	public void linkDiscoveryUpdate(LDUpdate update) {
		logger.info("++++++++++linkdiscoverupdate:" + update.getSrc() + ":" + update.getDst());
	}

	@Override
	public void linkDiscoveryUpdate(List<LDUpdate> updateList) {
		for(LDUpdate update : updateList) {
			logger.info("++++++++++----linkdiscoverupdate:" + update.getSrc() 
					+ "::" + update.getSrcPort() + ":" 
					+ update.getDst() + "::" + update.getDstPort());
			try {
				server.addSwLink(update.getSrc(), update.getSrcPort(), update.getDst(), update.getDstPort());
			} catch (RemoteException e) {
				e.printStackTrace();
			}
		}
	}

	@Override
	public void switchAdded(long switchId) {		
		logger.info("++++++++++switch added:" + switchId);
	}

	@Override
	public void switchRemoved(long switchId) {		
		logger.info("++++++++++switch removed:" + switchId);		
	}

	
	@Override
	public void switchActivated(long switchId) {
		logger.info("++++++++++switch actived:" + switchId);		
		IOFSwitch sw = floodlightProvider.getSwitch(switchId);
		String s = "";
		for(ImmutablePort port : sw.getPorts()) {
			Long addrLong = Ethernet.toLong(port.getHardwareAddress());
			String addr = HexString.toHexString(addrLong);
			s += port.getName() + " add:" + addr + " number:"+ port.getPortNumber() + " ";
			try {
				logger.info("adding port-switch map====>addr:" + addr + " name:" + port.getName() + " is on " + sw.getId());
				server.addPortSwitchMap(addr, port.getPortNumber(), sw.getId(), handler.id);
			} catch (RemoteException e) {
				e.printStackTrace();
			}
		}

		LinkedList<ImmutablePort> ports = new LinkedList<ImmutablePort>(sw.getPorts());
		swLocalCache.put(switchId, ports);
		logger.info("............adds:" + s);	
	}

	@Override
	public void switchPortChanged(long switchId, ImmutablePort port,
			PortChangeType type) {
		logger.info("++++++++++switch port changed:" + switchId);		
	}

	@Override
	public void switchChanged(long switchId) {
		logger.info("++++++++++switch changed:" + switchId);
	}

	public boolean sendSwFGDesc(
			HashMap<Long, LinkedList<SwitchFlowGroupDesc>> descmap)
			throws RemoteException {
		logger.info("SWFG desc Reveived!!!!");
		for(Long swid : descmap.keySet()) {
			logger.info("for swid:" + swid  + " " + descmap.get(swid));
		}
		currSwFgmap = descmap;
		return true;
	}
	
	//return a tunnel that should install this match
	private TunnelInfo getSwitchInstallByMatch(OFMatch match) {
		TunnelInfo tinfoToUse = null;
		try {
			Long srcMacLong = Ethernet.toLong(match.getDataLayerSource());
			Long dstMacLong = Ethernet.toLong(match.getDataLayerDestination());
			String srcMac = HexString.toHexString(srcMacLong);
			String dstMac = HexString.toHexString(dstMacLong);
			LinkedList<TunnelInfo> tinfolist = server.getTunnelInfoBySrcDst(srcMac, dstMac);
			logger.info("for " + srcMac + "->" + dstMac + " the tunnel info:" + tinfolist.size());
			if(tinfolist.size() == 0) {
				//meaning no tunnel is found for this match
				return null;
			}
			Long byteCount = flowStatByMatch.get(match);
			
			//IS THIS OKAY?
			if(byteCount == null)
				byteCount = new Long(0);
			//now we have a bunch of tunnels avaliable for this match!
			//pick one tunnel, acutally, anyone should be fine,
			//but we pick the one with sufficient but least bw avaliable
			//if unable to find a one with sufficient bw, pick the one with
			//greatest bw
			int index = 0;
			Long currBw = Long.MAX_VALUE;
			int idleindex = 0;
			Long currIdleBw = Long.MIN_VALUE;
			for(int i = 0;i<tinfolist.size();i++) {
				TunnelInfo tinfo = tinfolist.get(i);
				Long bw = tinfo.getBw();
				if(bw > byteCount && bw < currBw) {
					currBw = bw;
					index = i;
				}
				if(bw > currIdleBw) {
					currIdleBw = bw;
					idleindex = i;
				}
			}
			
			if(currBw == Long.MAX_VALUE) {
				//no tunnel has sufficient bw
				tinfoToUse = tinfolist.get(idleindex);
			} else {
				tinfoToUse = tinfolist.get(index);
			}	
		} catch (RemoteException e) {
			e.printStackTrace();
		}
		return tinfoToUse;
	}
	/*
	private HashMap<OFMatch, LinkedList<SwitchFlowGroupDesc>> matchMatchesToFgAllocations(HashMap<OFMatch, Long> flowStats) {
		//look at all the matches, see which fg they
		//belong to. Then assign those matches to the
		//paths that belong to this fg
		HashMap<OFMatch, LinkedList<SwitchFlowGroupDesc>> matchDescMap = 
				new HashMap<OFMatch, LinkedList<SwitchFlowGroupDesc>>();
		for(OFMatch match : flowStats.keySet()) {
			Long srcMacLong = Ethernet.toLong(match.getDataLayerSource());
			Long dstMacLong = Ethernet.toLong(match.getDataLayerDestination());
			String srcMac = HexString.toHexString(srcMacLong);
			String dstMac = HexString.toHexString(dstMacLong);
			try {
				Long srcSwid = server.getSwidByHostMac(srcMac);
				Long dstSwid = server.getSwidByHostMac(dstMac);
				String s = "for this match " + match 
						+ "--->src:" + srcSwid 
						+ " dst:" + dstSwid 
						+ " c:" + flowStatByMatch.get(match);
				logger.info(s);
				for(Long swid : swLocalCache) {
					//for each switch controlled by this controller, check
					//whether there is flow installation we need to do
					if(!currSwFgmap.containsKey(swid)) {
						logger.info("no fg installing for this:" + swid);
					} else {
						LinkedList<SwitchFlowGroupDesc> list = currSwFgmap.get(swid);

						for(SwitchFlowGroupDesc desc : list) {
							if(desc.getFgDstSwid().equals(dstSwid) && 
									desc.getFgSrcSwid().equals(srcSwid)) {
								logger.info("for sw" + swid + " there is something to do:" + desc + " for this match");
								if(matchDescMap.containsKey(match)) {
									matchDescMap.get(match).add(desc);
								} else {
									LinkedList<SwitchFlowGroupDesc> newlist = new LinkedList<SwitchFlowGroupDesc>();
									newlist.add(desc);
									matchDescMap.put(match, newlist);
								}
							}
						}
						logger.info("for this match" + match + "we want to install on sw:" + matchDescMap.get(match));
					}
				}				
			} catch (RemoteException e) {
				e.printStackTrace();
			}
		}
		return matchDescMap;
	}*/
	
	/*
	protected void createFlowForMatchByMatchDescMap(HashMap<OFMatch, LinkedList<SwitchFlowGroupDesc>> matchDescmap) {
		for(OFMatch match : matchDescmap.keySet()) {
			LinkedList<SwitchFlowGroupDesc> desclist = matchDescmap.get(match);
			//look though all desc, get the one with highest available bw!
			long currMostBw = Long.MAX_VALUE;
			int index = -1;
			long mostIdleBw = Long.MIN_VALUE;
			int mostIdleIndex = -1;
			for(int i = 0;i<desclist.size();i++) {
				SwitchFlowGroupDesc desc = desclist.get(i);
				//pick the one that has the least remaining but still satisfy requirement
				if(desc.getRemaining() < currMostBw && desc.getRemaining() > this.flowStatByMatch.get(match)) {
					index = i;
					currMostBw = desc.getRemaining();
				}
				if(desc.getRemaining() > mostIdleBw) {
					mostIdleIndex = i;
					mostIdleBw = desc.getRemaining();
				}
			}
			//at this point we know which switchDesc to use!!! can install flow!
			SwitchFlowGroupDesc descToUse;
			if(currMostBw == Long.MAX_VALUE) {
				//does not find any desc that has enough capacity, pick the 
				//most idle one
				descToUse = desclist.get(mostIdleIndex);
			} else {
				descToUse = desclist.get(index);
			}
			logger.info("TRY TO PUSH FLOW FOR MATCH:" + match + " ON DESC:" + descToUse);
			this.pushFlowModGivenSwFGDesc(match, descToUse);
		}
	}*/
	
	
	private void pushFlowToSwitches(LinkedList<Long> switches, OFMatch match, boolean reversePath) {
		OFMatch reverseMatch = match.clone().setDataLayerSource(match.getDataLayerDestination())
				.setDataLayerDestination(match.getDataLayerSource())
				.setNetworkSource(match.getNetworkDestination())
				.setNetworkDestination(match.getNetworkSource())
				.setTransportSource(match.getTransportDestination())
				.setTransportDestination(match.getTransportSource());
		for(int i = 1;i<switches.size() - 1;i++) {
			Long beforeswid = switches.get(i - 1);
			Long middleswid = switches.get(i);
			Long afterswid = switches.get(i + 1);
			//Long srcSwid = switches.get(i);
//			/Long dstSwid = switches.get(i + 1);

			if(!swLocalCache.containsKey(middleswid))
				continue;
			IOFSwitch sw = floodlightProvider.getSwitch(middleswid);
			try {
				Short portOnMiddleToBefore = server.getPortBySwid(middleswid, beforeswid);
				Short portOnMiddleToAfter = server.getPortBySwid(middleswid, afterswid);
				//Short outPort = server.getPortBySwid(srcSwid, dstSwid);
				//Short reverseOutPort = server.getPortBySwid(dstSwid, srcSwid);
				logger.info("from " + middleswid + " to before:" + beforeswid + " p " + portOnMiddleToBefore + " to after:" + afterswid + " p:" + portOnMiddleToAfter);
				if(portOnMiddleToBefore == null) {
					logger.info("NOTE could not find link from " + middleswid + " to " + beforeswid);
					return;
				}
				if(portOnMiddleToAfter == null) {
					logger.info("NOTE could not find link from " + middleswid + " to " + afterswid);
					return;
				}
				if(matchesWeHaveSeen.containsKey(middleswid)) {
					if(matchesWeHaveSeen.get(middleswid).contains(match)) {
						//do nothing
					} else {
						matchesWeHaveSeen.get(middleswid).add(match);
					}
				} else {
					LinkedList<OFMatch> list = new LinkedList<OFMatch>();
					list.add(match);
					matchesWeHaveSeen.put(middleswid, list);
				}

				match.setWildcards(((Integer)sw.getAttribute(IOFSwitch.PROP_FASTWILDCARDS)).intValue()
						& ~OFMatch.OFPFW_IN_PORT
						& ~OFMatch.OFPFW_DL_VLAN & ~OFMatch.OFPFW_DL_SRC & ~OFMatch.OFPFW_DL_DST
						& ~OFMatch.OFPFW_NW_SRC_MASK & ~OFMatch.OFPFW_NW_DST_MASK);
				this.writeFlowMod(
						sw, 
						OFFlowMod.OFPFC_ADD, 
						OFPacketOut.BUFFER_ID_NONE, 
						match.setInputPort(portOnMiddleToBefore), 
						portOnMiddleToAfter, 
						LocalController.CONFIG_INTERVAL);
				logger.info("on " + sw.getId() + " outport:" + portOnMiddleToAfter + " inport:" + portOnMiddleToBefore
				+" installing flow!!" + match);
				if(reversePath == true) {

					reverseMatch.setWildcards(((Integer)sw.getAttribute(IOFSwitch.PROP_FASTWILDCARDS)).intValue()
							& ~OFMatch.OFPFW_IN_PORT
							& ~OFMatch.OFPFW_DL_VLAN & ~OFMatch.OFPFW_DL_SRC & ~OFMatch.OFPFW_DL_DST
							& ~OFMatch.OFPFW_NW_SRC_MASK & ~OFMatch.OFPFW_NW_DST_MASK);
					this.writeFlowMod(sw, 
							OFFlowMod.OFPFC_ADD, -1, 
							reverseMatch.setInputPort(portOnMiddleToAfter), 
							portOnMiddleToBefore, 
							LocalController.CONFIG_INTERVAL);
					logger.info("installing flow!! reverse" + " outport:" + portOnMiddleToBefore + " inport:" + portOnMiddleToAfter
							+ reverseMatch);
				}
			} catch (RemoteException e) {
				e.printStackTrace();
			}
		}
		
		try {
			//for the last sw on the path, install flow on the port, this port should be connecting to host!
			Long dstMac = Ethernet.toLong(match.getDataLayerDestination());
			String dstMacString = HexString.toHexString(dstMac);
			Long dstHostSwid = server.getSwidByHostMac(dstMacString);
			if(swLocalCache.containsKey(dstHostSwid)) {
				Long dstNextHop = dstHostSwid.equals(switches.getLast())?switches.get(switches.size() - 2) : switches.get(1);
				installFlowToHostFromSwitch(dstMacString, dstHostSwid, dstNextHop, match);
			}
			//the same for the first sw on the path
			Long reversedstMac = Ethernet.toLong(match.getDataLayerSource());
			String reversedstMacString = HexString.toHexString(reversedstMac);
			Long reversedstHostSwid = server.getSwidByHostMac(reversedstMacString);
			if(swLocalCache.containsKey(reversedstHostSwid)) {
				Long reversedstNextHop = dstHostSwid.equals(switches.getLast())?switches.get(1) : switches.get(switches.size() - 2);
				installFlowToHostFromSwitch(reversedstMacString, reversedstHostSwid, reversedstNextHop, reverseMatch);
			}
		} catch(RemoteException e) {
			e.printStackTrace();
		}
	}
	
	private void installFlowToHostFromSwitch(String dstMacString, Long swid, Long nextHop, OFMatch match) {
		OFMatch reverseMatch = match.clone().setDataLayerSource(match.getDataLayerDestination())
				.setDataLayerDestination(match.getDataLayerSource())
				.setNetworkSource(match.getNetworkDestination())
				.setNetworkDestination(match.getNetworkSource())
				.setTransportSource(match.getTransportDestination())
				.setTransportDestination(match.getTransportSource());
		Short port = null;
		Short peerPort = null;
		try {
			 port = server.getPortOnSwByMac(swid, dstMacString, handler.id);
			 peerPort = server.getPortBySwid(swid, nextHop);
		} catch (RemoteException e) {
			e.printStackTrace();
		}
		if(port == null) {
			logger.info("NOTE could not find the mac " + dstMacString + " on that switch " + swid);
			return;
		}
		if(peerPort == null) {
			logger.info("NOTE " + swid + " could not find port to peer" + nextHop);
			return;
		}
		IOFSwitch sw = floodlightProvider.getSwitch(swid);
		writeFlowMod(sw, OFFlowMod.OFPFC_ADD, OFPacketOut.BUFFER_ID_NONE, match.setInputPort(peerPort), port, LocalController.CONFIG_INTERVAL);
		writeFlowMod(sw, OFFlowMod.OFPFC_ADD, OFPacketOut.BUFFER_ID_NONE, reverseMatch.setInputPort(port), peerPort, LocalController.CONFIG_INTERVAL);
		logger.info("on" + sw.getId() + " install to Host flow on outport:" + port + " inport:" +  peerPort + " for " + match);
		logger.info("on" + sw.getId() + " also reverse path:outport:" + peerPort + " inport:" + port + " for " + reverseMatch);
	}
	/*
	private void pushFlowModGivenSwFGDesc(OFMatch match, SwitchFlowGroupDesc desc) {
		Long srcSwid = desc.getSrc();
		Long dstSwid = desc.getDst();
		IOFSwitch sw = floodlightProvider.getSwitch(srcSwid);
		Short outPort = null;
		try {
			outPort = server.getPortBySwid(srcSwid, dstSwid);
		} catch (RemoteException e) {
			e.printStackTrace();
		}
		if(outPort == null) {
			logger.info("NOTE could not find port from " + srcSwid + " to " + dstSwid);
			return;
		}
		logger.info("PUSHING FLOW!!!!!!!!!!!!! FOR MATCH:" + match);
		this.writeFlowMod(sw, OFFlowMod.OFPFC_ADD, OFPacketOut.BUFFER_ID_NONE, match, outPort, LocalController.CONFIG_INTERVAL);
	}*/
	///////////////////////////////////////////////////////
	//////////////////////////////////////////////////////

	private Command processPacketInMessage(IOFSwitch sw, OFPacketIn pi, FloodlightContext cntx) {
		// Read in packet data headers by using OFMatch
		OFMatch match = new OFMatch();
		match.loadFromPacket(pi.getPacketData(), pi.getInPort());
		Long sourceMac = Ethernet.toLong(match.getDataLayerSource());
		Long destMac = Ethernet.toLong(match.getDataLayerDestination());
		
		Short vlan = match.getDataLayerVirtualLan();
		if ((destMac & 0xfffffffffff0L) == 0x0180c2000000L) {
			logger.info("ignoring packet addressed to 802.1D/Q reserved addr: switch {} vlan {} dest MAC {}",
					new Object[]{ sw, vlan, HexString.toHexString(destMac) });
			return Command.STOP;
		}
		if ((sourceMac & 0x010000000000L) == 0) {
			// If source MAC is a unicast address, learn the port for this MAC/VLAN
			this.addToPortMap(sw, sourceMac, vlan, pi.getInPort());
		}

		// Now output flow-mod and/or packet
		Short outPort = getFromPortMap(sw, destMac, vlan);
		if (outPort == null) {
			// If we haven't learned the port for the dest MAC/VLAN, flood it
			// Don't flood broadcast packets if the broadcast is disabled.
			// XXX For LearningSwitch this doesn't do much. The sourceMac is removed
			//     from port map whenever a flow expires, so you would still see
			//     a lot of floods.
			this.writePacketOutForPacketIn(sw, pi, OFPort.OFPP_FLOOD.getValue());
		} else if (outPort == match.getInputPort()) {
			logger.trace("ignoring packet that arrived on same port as learned destination:"
					+ " switch {} vlan {} dest MAC {} port {}",
					new Object[]{ sw, vlan, HexString.toHexString(destMac), outPort });
		} else {
			// Add flow table entry matching source MAC, dest MAC, VLAN and input port
			// that sends to the port we previously learned for the dest MAC/VLAN.  Also
			// add a flow table entry with source and destination MACs reversed, and
			// input and output ports reversed.  When either entry expires due to idle
			// timeout, remove the other one.  This ensures that if a device moves to
			// a different port, a constant stream of packets headed to the device at
			// its former location does not keep the stale entry alive forever.
			// FIXME: current HP switches ignore DL_SRC and DL_DST fields, so we have to match on
			// NW_SRC and NW_DST as well
			match.setWildcards(((Integer)sw.getAttribute(IOFSwitch.PROP_FASTWILDCARDS)).intValue()
					& ~OFMatch.OFPFW_IN_PORT
					& ~OFMatch.OFPFW_DL_VLAN & ~OFMatch.OFPFW_DL_SRC & ~OFMatch.OFPFW_DL_DST
					& ~OFMatch.OFPFW_NW_SRC_MASK & ~OFMatch.OFPFW_NW_DST_MASK);
			// We write FlowMods with Buffer ID none then explicitly PacketOut the buffered packet
			this.pushPacket(sw, match, pi, outPort);
			this.writeFlowMod(sw, OFFlowMod.OFPFC_ADD, OFPacketOut.BUFFER_ID_NONE, match, outPort, LocalController.FLOWMOD_DEFAULT_IDLE_TIMEOUT);
			if (LEARNING_SWITCH_REVERSE_FLOW) {
				this.writeFlowMod(sw, OFFlowMod.OFPFC_ADD, -1, match.clone()
						.setDataLayerSource(match.getDataLayerDestination())
						.setDataLayerDestination(match.getDataLayerSource())
						.setNetworkSource(match.getNetworkDestination())
						.setNetworkDestination(match.getNetworkSource())
						.setTransportSource(match.getTransportDestination())
						.setTransportDestination(match.getTransportSource())
						.setInputPort(outPort),
						match.getInputPort(), LocalController.FLOWMOD_DEFAULT_IDLE_TIMEOUT);
			}
			logger.info("$$$$$$$$$$$$$$$$$$write out flowmod$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$" + sw.getId() + ":" + outPort);
		}
		return Command.CONTINUE;
	}

	protected void addToPortMap(IOFSwitch sw, long mac, short vlan, short portVal) {
		Map<MacVlanPair,Short> swMap = macVlanToSwitchPortMap.get(sw);

		if (vlan == (short) 0xffff) {
			// OFMatch.loadFromPacket sets VLAN ID to 0xffff if the packet contains no VLAN tag;
			// for our purposes that is equivalent to the default VLAN ID 0
			vlan = 0;
		}

		if (swMap == null) {
			// May be accessed by REST API so we need to make it thread safe
			swMap = Collections.synchronizedMap(new LRULinkedHashMap<MacVlanPair,Short>(MAX_MACS_PER_SWITCH));
			macVlanToSwitchPortMap.put(sw, swMap);
		}
		swMap.put(new MacVlanPair(mac, vlan), portVal);
	}
	
	private void writePacketOutForPacketIn(IOFSwitch sw,
			OFPacketIn packetInMessage,
			short egressPort) {
		// from openflow 1.0 spec - need to set these on a struct ofp_packet_out:
		// uint32_t buffer_id; /* ID assigned by datapath (-1 if none). */
		// uint16_t in_port; /* Packet's input port (OFPP_NONE if none). */
		// uint16_t actions_len; /* Size of action array in bytes. */
		// struct ofp_action_header actions[0]; /* Actions. */
		/* uint8_t data[0]; */ /* Packet data. The length is inferred
    from the length field in the header.
    (Only meaningful if buffer_id == -1.) */

		OFPacketOut packetOutMessage = (OFPacketOut) floodlightProvider.getOFMessageFactory().getMessage(OFType.PACKET_OUT);
		short packetOutLength = (short)OFPacketOut.MINIMUM_LENGTH; // starting length

		// Set buffer_id, in_port, actions_len
		packetOutMessage.setBufferId(packetInMessage.getBufferId());
		packetOutMessage.setInPort(packetInMessage.getInPort());
		packetOutMessage.setActionsLength((short)OFActionOutput.MINIMUM_LENGTH);
		packetOutLength += OFActionOutput.MINIMUM_LENGTH;

		// set actions
		List<OFAction> actions = new ArrayList<OFAction>(1);
		actions.add(new OFActionOutput(egressPort, (short) 0));
		packetOutMessage.setActions(actions);

		// set data - only if buffer_id == -1
		if (packetInMessage.getBufferId() == OFPacketOut.BUFFER_ID_NONE) {
			byte[] packetData = packetInMessage.getPacketData();
			packetOutMessage.setPacketData(packetData);
			packetOutLength += (short)packetData.length;
		}

		// finally, set the total length
		packetOutMessage.setLength(packetOutLength);

		// and write it out
		try {
			//counterStore.updatePktOutFMCounterStoreLocal(sw, packetOutMessage);
			sw.write(packetOutMessage, null);
		} catch (IOException e) {
			logger.error("Failed to write {} to switch {}: {}", new Object[]{ packetOutMessage, sw, e });
		}
	}
	
    private void pushPacket(IOFSwitch sw, OFMatch match, OFPacketIn pi, short outport) {
        if (pi == null) {
            return;
        }

        // The assumption here is (sw) is the switch that generated the
        // packet-in. If the input port is the same as output port, then
        // the packet-out should be ignored.
        if (pi.getInPort() == outport) {
            if (logger.isDebugEnabled()) {
                logger.info("Attempting to do packet-out to the same " +
                          "interface as packet-in. Dropping packet. " +
                          " SrcSwitch={}, match = {}, pi={}",
                          new Object[]{sw, match, pi});
                return;
            }
        }

            logger.trace("PacketOut srcSwitch={} match={} pi={}",
                      new Object[] {sw, match, pi});

        OFPacketOut po =
                (OFPacketOut) floodlightProvider.getOFMessageFactory()
                                                .getMessage(OFType.PACKET_OUT);

        // set actions
        List<OFAction> actions = new ArrayList<OFAction>();
        actions.add(new OFActionOutput(outport, (short) 0xffff));

        po.setActions(actions)
          .setActionsLength((short) OFActionOutput.MINIMUM_LENGTH);
        short poLength =
                (short) (po.getActionsLength() + OFPacketOut.MINIMUM_LENGTH);

        // If the switch doens't support buffering set the buffer id to be none
        // otherwise it'll be the the buffer id of the PacketIn
        if (sw.getBuffers() == 0) {
            // We set the PI buffer id here so we don't have to check again below
            pi.setBufferId(OFPacketOut.BUFFER_ID_NONE);
            po.setBufferId(OFPacketOut.BUFFER_ID_NONE);
        } else {
            po.setBufferId(pi.getBufferId());
        }

        po.setInPort(pi.getInPort());

        // If the buffer id is none or the switch doesn's support buffering
        // we send the data with the packet out
        if (pi.getBufferId() == OFPacketOut.BUFFER_ID_NONE) {
            byte[] packetData = pi.getPacketData();
            poLength += packetData.length;
            po.setPacketData(packetData);
        }

        po.setLength(poLength);

        try {
            //counterStore.updatePktOutFMCounterStoreLocal(sw, po);
            sw.write(po, null);
        } catch (IOException e) {
            logger.error("Failure writing packet out", e);
        }
    }
    
    private void writeFlowMod(IOFSwitch sw, short command, int bufferId,
            OFMatch match, short outPort, short idleTimeout) {
        // from openflow 1.0 spec - need to set these on a struct ofp_flow_mod:
        // struct ofp_flow_mod {
        //    struct ofp_header header;
        //    struct ofp_match match; /* Fields to match */
        //    uint64_t cookie; /* Opaque controller-issued identifier. */
        //
        //    /* Flow actions. */
        //    uint16_t command; /* One of OFPFC_*. */
        //    uint16_t idle_timeout; /* Idle time before discarding (seconds). */
        //    uint16_t hard_timeout; /* Max time before discarding (seconds). */
        //    uint16_t priority; /* Priority level of flow entry. */
        //    uint32_t buffer_id; /* Buffered packet to apply to (or -1).
        //                           Not meaningful for OFPFC_DELETE*. */
        //    uint16_t out_port; /* For OFPFC_DELETE* commands, require
        //                          matching entries to include this as an
        //                          output port. A value of OFPP_NONE
        //                          indicates no restriction. */
        //    uint16_t flags; /* One of OFPFF_*. */
        //    struct ofp_action_header actions[0]; /* The action length is inferred
        //                                            from the length field in the
        //                                            header. */
        //    };

        OFFlowMod flowMod = (OFFlowMod) floodlightProvider.getOFMessageFactory().getMessage(OFType.FLOW_MOD);
        flowMod.setMatch(match);
        flowMod.setCookie(LearningSwitch.LEARNING_SWITCH_COOKIE);
        flowMod.setCommand(command);
        flowMod.setIdleTimeout(idleTimeout);
        flowMod.setHardTimeout(LocalController.FLOWMOD_DEFAULT_HARD_TIMEOUT);
        flowMod.setPriority(LocalController.FLOWMOD_PRIORITY);
        flowMod.setBufferId(bufferId);
        flowMod.setOutPort((command == OFFlowMod.OFPFC_DELETE) ? outPort : OFPort.OFPP_NONE.getValue());
        flowMod.setFlags((command == OFFlowMod.OFPFC_DELETE) ? 0 : (short) (1 << 0)); // OFPFF_SEND_FLOW_REM

        // set the ofp_action_header/out actions:
        // from the openflow 1.0 spec: need to set these on a struct ofp_action_output:
        // uint16_t type; /* OFPAT_OUTPUT. */
        // uint16_t len; /* Length is 8. */
        // uint16_t port; /* Output port. */
        // uint16_t max_len; /* Max length to send to controller. */
        // type/len are set because it is OFActionOutput,
        // and port, max_len are arguments to this constructor
        flowMod.setActions(Arrays.asList((OFAction) new OFActionOutput(outPort, (short) 0xffff)));
        flowMod.setLength((short) (OFFlowMod.MINIMUM_LENGTH + OFActionOutput.MINIMUM_LENGTH));

        if (logger.isTraceEnabled()) {
            logger.trace("{} {} flow mod {}",
                      new Object[]{ sw, (command == OFFlowMod.OFPFC_DELETE) ? "deleting" : "adding", flowMod });
        }

        //counterStore.updatePktOutFMCounterStoreLocal(sw, flowMod);

        // and write it out
        try {
            sw.write(flowMod, null);
        } catch (IOException e) {
            logger.error("Failed to write {} to switch {}", new Object[]{ flowMod, sw }, e);
        }
    }
    
    public Short getFromPortMap(IOFSwitch sw, long mac, short vlan) {
        if (vlan == (short) 0xffff) {
            vlan = 0;
        }
        Map<MacVlanPair,Short> swMap = macVlanToSwitchPortMap.get(sw);
        if (swMap != null)
            return swMap.get(new MacVlanPair(mac, vlan));

        // if none found
        return null;
    }
}