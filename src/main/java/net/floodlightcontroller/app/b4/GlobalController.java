package net.floodlightcontroller.app.b4;

import java.rmi.AlreadyBoundException;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.openflow.protocol.OFMessage;
import org.openflow.protocol.OFType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.floodlightcontroller.app.b4.rmi.FlowStatsDesc;
import net.floodlightcontroller.app.b4.rmi.RemoteGlobalConstant;
import net.floodlightcontroller.app.b4.rmi.RemoteLocalClient;
import net.floodlightcontroller.app.b4.rmi.SwitchFlowGroupDesc;
import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFMessageListener;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.linkdiscovery.ILinkDiscoveryService;
import net.floodlightcontroller.restserver.IRestApiService;

public class GlobalController  implements IOFMessageListener, IFloodlightModule, IFGTGConfigService {

	protected static Logger logger;
	protected AtomicInteger localid;

	protected IFloodlightProviderService floodlightProvider;

	ConcurrentHashMap<Integer, LocalHandler> allLocals;
	ConcurrentHashMap<Integer, RemoteLocalClient> allLocalHandlers;
	
	Thread worker;
	
	InformationBase informationBase;
	
	protected IRestApiService restApi;
	
	protected String configFilePath;

	class workerImpl implements Runnable {
		@Override
		public void run() {
			logger.info("starting worker.............");
			while(true) {
				for(Integer id : allLocals.keySet()) {
					try {
						if(!allLocalHandlers.containsKey(id)) {
							LocalHandler handler = allLocals.get(id);
							Registry registry = LocateRegistry.getRegistry("localhost", 
									handler.portToUse);
							RemoteLocalClient client = (RemoteLocalClient) registry.lookup(handler.name);
							logger.info(client.test());
							allLocalHandlers.put(id, client);
						} else {
							RemoteLocalClient client = allLocalHandlers.get(id);
							logger.info(client.test());
						}
					} catch (RemoteException e) {
						e.printStackTrace();
					} catch (NotBoundException e) {
						e.printStackTrace();
					}		

				}
				try {
					Thread.sleep(5000);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
		}
	}
	
	@Override
	public String getName() {
		return GlobalController.class.getSimpleName();
	}

	public LocalHandler getNextHandler() {
		LocalHandler lh = new LocalHandler();
		int id = localid.getAndAdd(1);
		int port = RemoteGlobalConstant.START_PORT + id;
		String name = "local-" + id;
		lh.id = id;
		lh.portToUse = port;
		lh.name = name;
		allLocals.put(lh.id, lh);
		return lh;
	}
	
	public boolean addHostSwitchMap(String mac, Long swid) {
		return informationBase.addHostSwitchMap(mac, swid);
	}
	
	public boolean addSwLink(Long src, Short srcPort, Long dst, Short dstPort) {
		return informationBase.addSwLink(src, srcPort, dst, dstPort);
	}
	
	public Long getSwitchByMac(String mac) {
		return informationBase.getSwitchByMac(mac);
	}
	
	public boolean addPortSwitchMap(String mac, Long swid, int id) {
		informationBase.addControllerSwMap(swid, id);
		return informationBase.addPortSwitchMap(mac, swid);
	}
	
	public void sendFlowDemand(HashMap<String, HashMap<String, FlowStatsDesc>> map, int id)
			throws RemoteException {
		// TODO Auto-generated method stub
		logger.info(">>>>>>>>>>>>>>>>>>>>Received FlowDemand!!!" + map.size() + " from " + id);
		for(String srcMAC : map.keySet()) {
			HashMap<String, FlowStatsDesc> destMap = map.get(srcMAC);
			for(String dstMAC : destMap.keySet()) {
				FlowStatsDesc desc = destMap.get(dstMAC); 
				this.informationBase.addByteCount(desc.getMatch(), desc.getCount());
			}
		}		
	}

	
	public Short getPortBySwid(Long srcSwid, Long dstSwid) {
		return this.informationBase.getPortBySwid(srcSwid, dstSwid);
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
		Collection<Class<? extends IFloodlightService>> l = new ArrayList<Class<? extends IFloodlightService>>();
	    l.add(IFGTGConfigService.class);
		return l;
	}

	@Override
	public Map<Class<? extends IFloodlightService>, IFloodlightService> getServiceImpls() {
		// TODO Auto-generated method stub
		Map<Class<? extends IFloodlightService>, IFloodlightService> m = new HashMap<Class<? extends IFloodlightService>, IFloodlightService>();
	    m.put(IFGTGConfigService.class, this);
		return m;
	}

	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
		Collection<Class<? extends IFloodlightService>> l =
				new ArrayList<Class<? extends IFloodlightService>>();
		l.add(IFloodlightProviderService.class);
		l.add(ILinkDiscoveryService.class);
		l.add(IFGTGConfigService.class);
		l.add(IRestApiService.class);
		return l;
	}

	@Override
	public void init(FloodlightModuleContext context)
			throws FloodlightModuleException {
		// TODO Auto-generated method stub
		allLocals = new ConcurrentHashMap<Integer, LocalHandler>();
		allLocalHandlers = new ConcurrentHashMap<Integer, RemoteLocalClient>();
		localid = new AtomicInteger(0);
		informationBase = new InformationBase();
		logger = LoggerFactory.getLogger(LocalController.class);

		restApi = context.getServiceImpl(IRestApiService.class);
		floodlightProvider = context.getServiceImpl(IFloodlightProviderService.class);
		try {
			GlobalRMIImpl impl = new GlobalRMIImpl(this);
			Registry registry = LocateRegistry.createRegistry(RemoteGlobalConstant.RMI_PORT);
			registry.bind(RemoteGlobalConstant.GLOBAL_ID, impl);
		} catch (RemoteException e) {
			e.printStackTrace();
		} catch (AlreadyBoundException e) {
			e.printStackTrace();
		}
		worker = new Thread(new workerImpl());
		worker.start();
		logger.info("---------global controller RMI registered");
	}

	@Override
	public void startUp(FloodlightModuleContext context)
			throws FloodlightModuleException {
		restApi.addRestletRoutable(new FGTGApiRegisterHelper());
	}

	@Override
	public net.floodlightcontroller.core.IListener.Command receive(
			IOFSwitch sw, OFMessage msg, FloodlightContext cntx) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void setConfigFile(String filepath) {
		//every time config is reset, we recompute the whole thing, this is where all things start!
		configFilePath = filepath;
		logger.debug("===============>>>>>>>>>>>>>>>>>>>>>>>>configfile set to:" + configFilePath);
		informationBase.readConfigFromFile(filepath);
		informationBase.computeTGBwAllocation();
		informationBase.setUpMatches();
		processAndSend();
	}
	
	public Long getSwidByHostMac(String mac) {
		return informationBase.getSwidByHostMac(mac);
	}
	
	public void processAndSend() {
		HashMap<Integer, HashMap<Long, LinkedList<SwitchFlowGroupDesc>>> swfgdescmap = 
				informationBase.getSwitchFGDesc();
		if(swfgdescmap == null) {
			logger.debug("NOTE null sw fg map");
			return;
		}
		for(Integer conid : swfgdescmap.keySet()) {
			logger.debug("Sending swfg info to :" + conid);
			try {
				allLocalHandlers.get(conid).sendSwFGDesc(swfgdescmap.get(conid));
			} catch (RemoteException e) {
				e.printStackTrace();
			}
		}
	}

}
