package net.floodlightcontroller.app.b4;

import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.Collection;
import java.util.Map;

import org.openflow.protocol.OFMessage;
import org.openflow.protocol.OFType;

import net.floodlightcontroller.app.b4.rmi.RemoteGlobalConstant;
import net.floodlightcontroller.app.b4.rmi.RemoteGlobalServer;
import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.IOFMessageListener;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.core.IFloodlightProviderService;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentSkipListSet;

import net.floodlightcontroller.linkdiscovery.ILinkDiscoveryService;
import net.floodlightcontroller.packet.Ethernet;

import org.openflow.util.HexString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LocalController implements IOFMessageListener, IFloodlightModule {

	protected IFloodlightProviderService floodlightProvider;
	protected ILinkDiscoveryService linkDiscoverer;
	protected ConcurrentSkipListSet<Long> macAddresses;
	protected static Logger logger;
	protected Map<String, String> configParams;
	
	protected boolean remote;
	
	protected Thread worker;
	
	class workerThread implements Runnable {
		@Override
		public void run() {
			
		}		
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
		return l;
	}

	@Override
	public void init(FloodlightModuleContext context)
			throws FloodlightModuleException {    
		floodlightProvider = context.getServiceImpl(IFloodlightProviderService.class);
		linkDiscoverer = context.getServiceImpl(ILinkDiscoveryService.class);
		macAddresses = new ConcurrentSkipListSet<Long>();
		logger = LoggerFactory.getLogger(LocalController.class);
		configParams = context.getConfigParams(this);
		String global = configParams.get("global");
		logger.info("---------------serverport:" + global);
		if(global != null && global.equals("enabled")) {
			remote = true;
		} else {
			remote = false;
		}
	}

	@Override
	public void startUp(FloodlightModuleContext context) {
		floodlightProvider.addOFMessageListener(OFType.PACKET_IN, this);
		try {
			Registry registry = LocateRegistry.getRegistry("localhost", 
					RemoteGlobalConstant.RMI_PORT);
			RemoteGlobalServer server = (RemoteGlobalServer) registry.lookup(RemoteGlobalConstant.GLOBAL_ID);
			int id = server.contact();
			logger.info("self-reigstered to global! Got id:" + id);
		} catch (RemoteException e) {
			e.printStackTrace();
		} catch (NotBoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		if(remote == true) {
			logger.info("#######registering self to global!");
			
		} else {
			logger.info("#######do not connect to remote!");
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
			logger.info("MAC Address: {} seen on switch: {}",
					HexString.toHexString(sourceMACHash),
					sw.getId());
		}	
		ConcurrentSkipListSet<Long> dpids = new ConcurrentSkipListSet<Long>(floodlightProvider.getAllSwitchDpids());
		String alldpids = "";
		for(Long dpid : dpids) {
			alldpids += dpid + " ";
		}
		logger.info("---------------alldpids:" + alldpids);
		logger.info("################################ of links:" + 
		linkDiscoverer.getLinks().size() + ":" + 
				linkDiscoverer.getSwitchLinks());
		return Command.CONTINUE;
	}

}