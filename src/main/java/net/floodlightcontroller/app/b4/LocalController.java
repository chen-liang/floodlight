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
import org.openflow.protocol.action.OFAction;
import org.openflow.protocol.action.OFActionOutput;
import org.openflow.protocol.statistics.OFFlowStatisticsReply;
import org.openflow.protocol.statistics.OFFlowStatisticsRequest;
import org.openflow.protocol.statistics.OFStatistics;
import org.openflow.protocol.statistics.OFStatisticsType;

import net.floodlightcontroller.app.b4.rmi.RemoteGlobalConstant;
import net.floodlightcontroller.app.b4.rmi.RemoteGlobalServer;
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
import net.floodlightcontroller.packet.Ethernet;
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


	protected ConcurrentSkipListSet<Long> macAddresses;
	protected ConcurrentSkipListSet<Long> swLocalCache;
	protected static Logger logger;
	protected Map<String, String> configParams;

	protected boolean remote;
	RemoteGlobalServer server;

	protected Thread worker;

	protected LocalHandler handler;//a descriptor of self	

	int rmi_serverport;
	String rmi_serverhost;


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
    
    protected HashMap<String, HashMap<String,Long>> computeFlowDemand(LinkedList<OFStatistics> values) {
    	if(values.size() == 0) 
    		return null;
    	
    	//whether makes this a member variable? by doing so we can record all histories, but do we want to do that?
    	HashMap<String, HashMap<String,Long>> map = new HashMap<String, HashMap<String,Long>>();
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
    		HashMap<String, Long> destMap;
    		if(map.containsKey(sourceMac)) {
    			destMap = map.get(sourceMac);
    		} else {
    			destMap = new HashMap<String, Long>();
    		}
    		destMap.put(destMac, fstats.getByteCount());
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
				logger.info("**********************querying for stats!!!" + swLocalCache.size());
				for(Long swid : swLocalCache) {
					IOFSwitch sw = floodlightProvider.getSwitch(swid);
					LinkedList<OFStatistics> values = new LinkedList<OFStatistics>(getStats(sw));
					if(values.size() > 0) {
						OFStatistics stat = values.getFirst();
						String s = "***************************" + swid + ":" + values.size() + ":" + stat.getClass().getCanonicalName();

						if(stat instanceof OFFlowStatisticsReply) {
							OFFlowStatisticsReply flowstat = (OFFlowStatisticsReply)stat;							
							s += "->" + flowstat.getByteCount() + "+++";
						}
						logger.info(s);
						try {
							server.sendFlowDemand(computeFlowDemand(values), handler.id);
						} catch (RemoteException e) {
							e.printStackTrace();
						}
					} else {
						logger.info("***************************" + swid);
					}
				}
				try {
					Thread.sleep(3000);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
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
		macVlanToSwitchPortMap =
				new ConcurrentHashMap<IOFSwitch, Map<MacVlanPair,Short>>();
		floodlightProvider = context.getServiceImpl(IFloodlightProviderService.class);
		floodlightProvider.addOFSwitchListener(this);
		linkDiscoverer = context.getServiceImpl(ILinkDiscoveryService.class);
		linkDiscoverer.addListener(this);
		deviceManager = context.getServiceImpl(IDeviceService.class);
		macAddresses = new ConcurrentSkipListSet<Long>();
		swLocalCache = new ConcurrentSkipListSet<Long>();
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
			logger.info("MAC Address: {} seen on switch: {}",
					macadd,
					sw.getId());
			logger.info("the ports:" + s);
			try {
				server.addHostSwitchMap(macadd, sw.getId());
			} catch (RemoteException e) {
				e.printStackTrace();
			}
		}	

		/*ConcurrentSkipListSet<Long> dpids = new ConcurrentSkipListSet<Long>(floodlightProvider.getAllSwitchDpids());
		String alldpids = "";
		for(Long dpid : dpids) {
			alldpids += dpid + " ";
		}
		logger.info("---------------alldpids:" + alldpids);
		logger.info("################################ of links:" + 
		linkDiscoverer.getLinks().size() + ":" + 
				linkDiscoverer.getSwitchLinks());*/
		Command c = Command.CONTINUE;
		if(msg.getType() == OFType.PACKET_IN) {
			c = this.processPacketInMessage(sw, (OFPacketIn)msg, cntx);
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
			/*logger.info("++++++++++----linkdiscoverupdate:" + update.getSrc() 
					+ "::" + update.getSrcPort() + ":" 
					+ update.getDst() + "::" + update.getDstPort());
			 */
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
		swLocalCache.add(switchId);
		
		IOFSwitch sw = floodlightProvider.getSwitch(switchId);
		String s = "";
		for(ImmutablePort port : sw.getPorts()) {
			String addr = HexString.toHexString(port.getHardwareAddress());
			s += port.getName() + ":" + addr + " ";
			try {
				logger.info("adding port-switch map====>addr:" + addr + " name:" + port.getName() + " is on " + sw.getId());
				server.addPortSwitchMap(addr, sw.getId());
			} catch (RemoteException e) {
				e.printStackTrace();
			}

		}
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
			this.writeFlowMod(sw, OFFlowMod.OFPFC_ADD, OFPacketOut.BUFFER_ID_NONE, match, outPort);
			if (LEARNING_SWITCH_REVERSE_FLOW) {
				this.writeFlowMod(sw, OFFlowMod.OFPFC_ADD, -1, match.clone()
						.setDataLayerSource(match.getDataLayerDestination())
						.setDataLayerDestination(match.getDataLayerSource())
						.setNetworkSource(match.getNetworkDestination())
						.setNetworkDestination(match.getNetworkSource())
						.setTransportSource(match.getTransportDestination())
						.setTransportDestination(match.getTransportSource())
						.setInputPort(outPort),
						match.getInputPort());
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
			counterStore.updatePktOutFMCounterStoreLocal(sw, packetOutMessage);
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
                logger.debug("Attempting to do packet-out to the same " +
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
            counterStore.updatePktOutFMCounterStoreLocal(sw, po);
            sw.write(po, null);
        } catch (IOException e) {
            logger.error("Failure writing packet out", e);
        }
    }
    
    private void writeFlowMod(IOFSwitch sw, short command, int bufferId,
            OFMatch match, short outPort) {
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
        flowMod.setIdleTimeout(LocalController.FLOWMOD_DEFAULT_IDLE_TIMEOUT);
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

        counterStore.updatePktOutFMCounterStoreLocal(sw, flowMod);

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