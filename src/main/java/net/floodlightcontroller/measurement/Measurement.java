package net.floodlightcontroller.measurement;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.rmi.AlreadyBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.openflow.protocol.OFType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.floodlightcontroller.app.b4.LocalController;
import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;

public class Measurement implements IFloodlightModule, IMeasurementServices {

	IFloodlightProviderService floodlightProvider;
	
	ArrayList<String> nameToFilter;
	
	Map<String, String> configParams;
	
	double alpha;// used to adjust time fraction
	final double ALPHA_DEFAULT = 0;
	final int RMI_PORT = 33333;
	final String RMI_ID = "floodlightmeasurement";
	
	protected static Logger logger;
	
	private static String counterlogPath = "/home/chen/fleventCount/";
	private BufferedWriter counterlogWriter = null;
	private int eventCount = 0;
	private static int intervalMsec = 50;
	
	////////////////TIMES///////////////
//	/protected long totalCpuTimeLastTime;
	protected long totalSystemTimeLastTime;
	protected long currentNonHandlerCpuTime;//total cpu time spent on non-handler/num of sw
	protected HashMap<Long, Long> totalAllSwitchOnHandlerCpuTime; 
	

	//key handlername, value <key swid, value <type, times on that>>
	ConcurrentHashMap<String, ConcurrentHashMap<Long, ConcurrentHashMap<OFType,Times>>> timesMap;
	
	ConcurrentHashMap<String, Long> threadCpuTime;
	
	class Times {
		long cpuStart;
		long cpuEnd;
		long sysStart;
		long sysEnd;
		
		long currentTotalCpuTime;
		long currentTotalSysTime;
		
		//double fraction;
		
		public boolean isAvaliable() {
			return (cpuStart != -1) && (cpuEnd != -1) 
					&& (sysStart != -1) && (sysEnd != -1);
		}
		
		public void cumulateTimes() {
			if(!this.isAvaliable()) {
				logger.info("NOTE: trying to cumulateTimes while not avaliable!");
				return;
			}			
			currentTotalCpuTime += getElapsedCPUTime();
			currentTotalSysTime += getElapsedSystemTime();
			logger.info("add time! cpu:" + getElapsedCPUTime() + " sys:" + getElapsedSystemTime() + " new cpu:" + currentTotalCpuTime);
			reset();
		}
		
		private void reset() {
			cpuStart = -1;
			cpuEnd = -1;
			sysStart = -1;
			sysEnd = -1;
		}
		
		public long getCurrentTotalCpu() {
			return currentTotalCpuTime;
		}
		
		public long getElapsedCPUTime() {
			if(cpuStart == -1 || cpuEnd == -1) {
				logger.info("NOTE: querying cpu time, but no such info!");
				return -1;
			}
			return cpuEnd - cpuStart;
		}
		
		public long getElapsedSystemTime() {
			if(sysStart == -1 || sysEnd == -1) {
				logger.info("NOTE: querying sys time, but no such info!");
				return -1;
			}
			return sysEnd - sysStart;
		}
		
		public Times() {
			cpuStart = -1;
			cpuEnd = -1;
			sysStart = -1;
			sysEnd = -1;
			//fraction = -1;
			currentTotalCpuTime = 0;
			currentTotalSysTime = 0;
		}
	}
	
	//TODO need to have a mechanism that clears data!!!
	
	private void clearCumulativeTimes() {
		for(String lname : timesMap.keySet()) {
			ConcurrentHashMap<Long, ConcurrentHashMap<OFType, Times>> swmap = timesMap.get(lname);
			for(Long swid : swmap.keySet()) {
				ConcurrentHashMap<OFType, Times> timesmap = swmap.get(swid);
				for(Times times : timesmap.values()) {
					times.currentTotalCpuTime = 0;
					times.currentTotalSysTime = 0;
					times.reset();
				}			
			}
		}
	}
	
	public HashMap<Long, Long> getAllSwitchHandlerCPUTime() {
		logger.info("compute cpu handler time for all");
		HashMap<Long, Long> totalSwCpuTime = new HashMap<Long, Long>();
		for(String lname : timesMap.keySet()) {
			ConcurrentHashMap<Long, ConcurrentHashMap<OFType, Times>> swmap = timesMap.get(lname);
			for(Long swid : swmap.keySet()) {
				ConcurrentHashMap<OFType, Times> timesmap = swmap.get(swid);
				long currCpuTime = 0;
				for(Times times : timesmap.values()) {
					if(times.getCurrentTotalCpu() != -1) {
						logger.info("for " + swid + " add :" + times.getCurrentTotalCpu());
						currCpuTime += times.getCurrentTotalCpu();
					}
				}
				if(totalSwCpuTime.containsKey(swid))
					totalSwCpuTime.put(swid, totalSwCpuTime.get(swid) + currCpuTime);
				else
					totalSwCpuTime.put(swid, currCpuTime);
				logger.info("now that:" + swid + ":" + totalSwCpuTime.get(swid));
			}
		}
		return totalSwCpuTime;
	}
	
	public HashMap<Long, Double> getAllSwitchHandlerCPUTimeFraction() {
		HashMap<Long, Long> totalSwCpuTime = new HashMap<Long, Long>();
		HashMap<Long, Long> totalSwSysTime = new HashMap<Long, Long>();
		HashMap<Long, Double> fractions = new HashMap<Long, Double>();
		for(String lname : timesMap.keySet()) {
			ConcurrentHashMap<Long, ConcurrentHashMap<OFType, Times>> swmap = timesMap.get(lname);
			for(Long swid : swmap.keySet()) {
				ConcurrentHashMap<OFType, Times> timesmap = swmap.get(swid);
				long currCpuTime = 0;
				long currSysTime = 0;
				for(Times times : timesmap.values()) {
					if(times.getCurrentTotalCpu() != -1)
						currCpuTime += times.getCurrentTotalCpu();
					if(times.getElapsedSystemTime() != -1)
						currSysTime += times.getElapsedSystemTime();
				}
				if(totalSwCpuTime.containsKey(swid))
					totalSwCpuTime.put(swid, totalSwCpuTime.get(swid) + currCpuTime);
				else
					totalSwCpuTime.put(swid, currCpuTime);
				
				if(totalSwSysTime.containsKey(swid))
					totalSwSysTime.put(swid, totalSwSysTime.get(swid) + currSysTime);
				else
					totalSwSysTime.put(swid, currSysTime);
			}
		}
		if(totalSwCpuTime.size() != totalSwSysTime.size())
			logger.info("NOTE: unmatching time info");
		for(Long swid : totalSwCpuTime.keySet()) {
			long totalCpuTime = totalSwCpuTime.get(swid);
			if(!totalSwSysTime.containsKey(swid)) {
				logger.info("NOTE: has cpu time info but no sys time info");
				continue;
			}
			long totalSysTime = totalSwSysTime.get(swid);
			double fraction = (double)totalCpuTime/(double)totalSysTime;
			fractions.put(swid, fraction);
		}
		return fractions;
	}
	
	
	/*private long getCurrentAllThreadTime() {
		ThreadMXBean tbean = ManagementFactory.getThreadMXBean();
		long[] allids = tbean.getAllThreadIds();
		long time = 0;
		for(long id : allids) {
			String name = tbean.getThreadInfo(id).getThreadName();
			long curTime = tbean.getThreadCpuTime(id);
			if(threadCpuTime.containsKey(name)) {
				time += curTime;
			}
			threadCpuTime.put(name, curTime);			
		}
		return time;
	}*/
	
	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleServices() {
		Collection<Class<? extends IFloodlightService>> l = 
				new ArrayList<Class<? extends IFloodlightService>>();
		l.add(IMeasurementServices.class);
		return l;
	}

	@Override
	public Map<Class<? extends IFloodlightService>, IFloodlightService> getServiceImpls() {
		Map<Class<? extends IFloodlightService>,
        IFloodlightService> m = 
            new HashMap<Class<? extends IFloodlightService>,
                        IFloodlightService>();
        // We are the class that implements the service
        m.put(IMeasurementServices.class, this);
        return m;
	}

	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
		Collection<Class<? extends IFloodlightService>> l = 
                new ArrayList<Class<? extends IFloodlightService>>();
        l.add(IMeasurementServices.class);
        return l;
	}

	@Override
	public void init(FloodlightModuleContext context)
			throws FloodlightModuleException {
		floodlightProvider = context
                .getServiceImpl(IFloodlightProviderService.class);
		configParams = context.getConfigParams(this);
		currentNonHandlerCpuTime = 0;
		totalSystemTimeLastTime = 0;
		totalAllSwitchOnHandlerCpuTime = new HashMap<Long, Long>();
		threadCpuTime = new ConcurrentHashMap<String, Long>();
		timesMap = new ConcurrentHashMap<String, ConcurrentHashMap<Long, ConcurrentHashMap<OFType,Times>>>();
		nameToFilter = new ArrayList<String>();
		nameToFilter.add("firewall");
		nameToFilter.add("linkdiscovery");
		nameToFilter.add("topology");
		nameToFilter.add("devicemanager");
		nameToFilter.add("loadbalancer");
		
		nameToFilter.add("staticflowentry");
		//totalCpuTimeLastTime = -1;
		logger = LoggerFactory.getLogger(LocalController.class);
		if(configParams.containsKey("alpha")) {
			alpha = Double.parseDouble(configParams.get("alpha"));
		} else {
			alpha = ALPHA_DEFAULT;
		}
		
		try {
			int rmiport;
			String rmiid;
			if(configParams.containsKey("rmiport")) {
				logger.info("Measurement using rmi port:" + configParams.get("rmiport"));
				rmiport = Integer.parseInt(configParams.get("rmiport"));
			} else {
				logger.info("Measurement using default port:" + RMI_PORT);
				rmiport = RMI_PORT;
			}
			if(configParams.containsKey("rmiid")) {
				logger.info("Measurement using rmi id:" + configParams.get("rmiid"));
				rmiid = configParams.get("rmiid");
			} else {
				logger.info("Measurement using default id:" + RMI_ID);
				rmiid = RMI_ID;
			}
			MeasurementWorkerImpl impl = new MeasurementWorkerImpl(this);
			Registry registry;
			registry = LocateRegistry.createRegistry(rmiport);
			registry.bind(rmiid, impl);
		} catch (RemoteException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (AlreadyBoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	@Override
	public void startUp(FloodlightModuleContext context)
			throws FloodlightModuleException {
		// TODO Auto-generated method stub
		
	}

	private Times getTimesInfo(Long swid, OFType type, String listenerName) {
		//a helper function to avoid have same piece code for the 
		//functions below
		//return a Times, if exist, return a reference
		//otherwise, create, add to map, return a reference
		//logger.info("gettimesinfo called by:" + listenerName);
		Times times;
		if(timesMap.containsKey(listenerName)) {
			ConcurrentHashMap<Long, ConcurrentHashMap<OFType, Times>> map = timesMap.get(listenerName);
			if(map.containsKey(swid)) {
				ConcurrentHashMap<OFType, Times> typemap = map.get(swid);
				if(typemap.containsKey(type)) {
					times = typemap.get(type);
				} else {
					times = new Times();
					typemap.put(type, times);
				}
			} else {
				times = new Times();
				ConcurrentHashMap<OFType, Times> typemap = 
						new ConcurrentHashMap<OFType, Times>();
				typemap.put(type, times);
				map.put(swid, typemap);
				timesMap.put(listenerName, map);
			}
		} else {
			logger.info("create new times! for " + swid);
			times = new Times();
			ConcurrentHashMap<OFType, Times> typemap = 
					new ConcurrentHashMap<OFType, Times>();
			typemap.put(type, times);
			ConcurrentHashMap<Long, ConcurrentHashMap<OFType, Times>> map = 
					new ConcurrentHashMap<Long, ConcurrentHashMap<OFType,Times>>();
			map.put(swid, typemap);
			timesMap.put(listenerName, map);
		}
		return times;
 	}
	
	@Override
	public void recordStartSystemTime(Long swid, OFType type, String listenerName, long time) {
		if(nameToFilter.contains(listenerName))
			return;
		logger.info("system start lname:" + listenerName + " sw:" + swid);
		Times times = getTimesInfo(swid, type, listenerName);
		times.sysStart = time;
		if(times.isAvaliable()) {
			times.cumulateTimes();
		}
	}

	@Override
	public void recordStartCPUTime(Long swid, OFType type, String listenerName, long time) {
		if(nameToFilter.contains(listenerName))
			return;
		logger.info("cpu start lname:" + listenerName + " sw:" + swid);
		Times times = getTimesInfo(swid, type, listenerName);
		times.cpuStart = time;
		if(times.isAvaliable()) {
			times.cumulateTimes();
		}
	}

	@Override
	public void recordEndSystemTime(Long swid, OFType type, String listenerName, long time) {
		if(nameToFilter.contains(listenerName))
			return;
		logger.info("system end lname:" + listenerName + " sw:" + swid);
		Times times = getTimesInfo(swid, type, listenerName);
		times.sysEnd = time;
		if(times.isAvaliable()) {
			times.cumulateTimes();
		}
	}

	@Override
	public void recordEndCPUTime(Long swid, OFType type, String listenerName, long time) {
		if(nameToFilter.contains(listenerName))
			return;
		logger.info("cpu end lname:" + listenerName + " sw:" + swid);
		Times times = getTimesInfo(swid, type, listenerName);
		times.cpuEnd = time;
		if(times.isAvaliable()) {
			times.cumulateTimes();
		}
	}

	@Override
	synchronized public void recordStart(Long swid, OFType type, String listenerName,
			long cpustart, long sysstart) {
		if(nameToFilter.contains(listenerName))
			return;
		Times times = getTimesInfo(swid, type, listenerName);
		times.cpuStart = cpustart;
		times.sysStart = sysstart;
//		ThreadMXBean tbean = ManagementFactory.getThreadMXBean();
//		String s = "\n";
//		for(long id : tbean.getAllThreadIds()) {
//			s += tbean.getThreadInfo(id).getThreadName() +  "##" + tbean.getThreadCpuTime(id) + "\n";
//		}
//		logger.info("num of thread:"+ tbean.getAllThreadIds().length  + " they are:" + s + "\n record start lname:" + listenerName + " sw:" + swid + " cpu:" + cpustart);
		/*if(times.isAvaliable()) {
			times.cumulateTimes();
		}*/
	}

	class EventCounter implements Runnable {

		@Override
		public void run() {
			while(true) {
				try {					
					counterlogWriter.write(System.currentTimeMillis() + ":" + eventCount + "\n");
					counterlogWriter.flush();
					eventCount = 0;
					Thread.sleep(intervalMsec);
				} catch (InterruptedException e) {
					e.printStackTrace();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}			
		}
		
	}
	
	@Override
	synchronized public void recordEnd(Long swid, OFType type, String listenerName,
			long cpuend, long sysend) {

		/////////////////////////////////
		if(counterlogWriter == null) {
			try {
				File f = new File(counterlogPath + floodlightProvider.getAllSwitchDpids().toString());
				if(!f.exists()) {
					f.createNewFile();
				}
				FileWriter fstream = new FileWriter(f.getAbsoluteFile());
				counterlogWriter = new BufferedWriter(fstream);
				eventCount = 0;
				Thread worker = new Thread(new EventCounter());
				worker.start();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		eventCount ++;
		//////////////////////////////
		
		if(nameToFilter.contains(listenerName))
			return;
		Times times = getTimesInfo(swid, type, listenerName);
		times.cpuEnd = cpuend;
		times.sysEnd = sysend;

//		ThreadMXBean tbean = ManagementFactory.getThreadMXBean();
//		String s = "\n";
//		for(long id : tbean.getAllThreadIds()) {
//			s += tbean.getThreadInfo(id).getThreadName()  + "##" + tbean.getThreadCpuTime(id) + "\n";
//		}
//		logger.info("num of thread:" + tbean.getAllThreadIds().length  + " they are:" + s + "\n record end lname:" + listenerName + " sw:" + swid + " cpu:" + cpuend + " minus:" + times.getElapsedCPUTime());
		if(times.isAvaliable()) {
			times.cumulateTimes();
		}
	}
	
	private ConcurrentHashMap<String, Long> getAllCurrentThreadCpuTime() {
		logger.info(">>>>>>>>>>>>>>>>>>>>>>>>>>calling get all thread cpu time!");
		ThreadMXBean tbean = ManagementFactory.getThreadMXBean();
		ConcurrentHashMap<String, Long> threadcpumap = new ConcurrentHashMap<String, Long>();
		long[] allids = tbean.getAllThreadIds();
		for(long id : allids) {
			long time = tbean.getThreadCpuTime(id);
			String name = tbean.getThreadInfo(id).getThreadName();
			threadcpumap.put(name, time);
		}
		return threadcpumap;
	}
	
	public FloodlightMeasurementInfo getInfoAndRefresh() {
		if(threadCpuTime.size() == 0 || totalSystemTimeLastTime == 0) {
			logger.info(">>>>>>>>>>>first time initialize as:" + threadCpuTime.size() + ":" + totalSystemTimeLastTime);
			//no info at this point, meaning no fraction can be computed,
			//record some state, return
			threadCpuTime = getAllCurrentThreadCpuTime();
			
//			totalCpuTimeLastTime = 0;//getCurrentAllThreadTime();
//			for(long time : threadCpuTime.values()) {
//				totalCpuTimeLastTime += time;
//			}
			totalAllSwitchOnHandlerCpuTime = getAllSwitchHandlerCPUTime();
			totalSystemTimeLastTime = System.nanoTime();
			return null;
		}
		
		FloodlightMeasurementInfo info = getInfo();
		
		logger.info("INFO^^^^^^^^^^^^^^^^^^^^^^^^^non time:" + currentNonHandlerCpuTime + " frack:" + info.getHandlerFraction().keySet()
				+ " frac:" + info.getHandlerFraction().values() +" switches:" + info.getAllSwitch());
		
		//reset some states
		totalAllSwitchOnHandlerCpuTime = getAllSwitchHandlerCPUTime();
		currentNonHandlerCpuTime = 0;
		totalSystemTimeLastTime = System.nanoTime();
		clearCumulativeTimes();
		timesMap.clear();	
		
		return info;
	}
	
	private FloodlightMeasurementInfo getInfo() {
		
		FloodlightMeasurementInfo info = new FloodlightMeasurementInfo();
		
		HashMap<Long, Double> allFractions = new HashMap<Long, Double>();

		HashMap<Long, Long> currentAllSwitchOnHandlerCpuTime = getAllSwitchHandlerCPUTime();
		
		ConcurrentHashMap<String, Long> map = getAllCurrentThreadCpuTime();
		String ss = "";
		long totalCpuTimeElapsed = 0;//getCurrentAllThreadTime();
		long totalSysElapsedTime;
		for(String key : map.keySet()) {
			if(threadCpuTime.containsKey(key)) {
				//if this a thread we saw also last time
				ss += key + "##" + map.get(key) + " - " + threadCpuTime.get(key) + "\n";
				totalCpuTimeElapsed += map.get(key) - threadCpuTime.get(key);
			} else {
				ss += key + "##" + map.get(key) + "\n";
				totalCpuTimeElapsed += map.get(key);
			}
		}
		for(String key : threadCpuTime.keySet()) {
			if(!map.containsKey(key)) {
				//this is a thread that disappeared in the last interval
				//we use the average cpu time of other thread as its value
				long offset = totalCpuTimeElapsed/map.size();
				totalCpuTimeElapsed += (threadCpuTime.get(key) + offset);
				ss += key + "-##" + offset + "\n";
			}
		}
		ss += "total:" + totalCpuTimeElapsed + "\n";
		//logger.info("computing fraction, current cpu time all:" + map.size() + "->\n" + ss);
		threadCpuTime = map;
		
		//long totalCpuTimeElapsed =  currentTotalTime;// - totalCpuTimeLastTime;
		//totalCpuTimeLastTime = currentTotalTime;
		
		
		logger.info("handler time: current:" + currentAllSwitchOnHandlerCpuTime.keySet() 
				+ " last:" + totalAllSwitchOnHandlerCpuTime.keySet());
		
		//try to compute elapsed cpu time for all switch
		//note there current map may be different from old map,
		//for example, old map has swid 3, but during the time
		//between this call and last one, no computation goes through swid3 
		//and swid 3 has not been recorded in this call and this call only has
		//swid2, in this case. 
		
		if(currentAllSwitchOnHandlerCpuTime.size() != 0) {
			
			long totalCpuTimeOnHandler = 0;
			for(Long value : currentAllSwitchOnHandlerCpuTime.values()) {
				totalCpuTimeOnHandler += value;
			}
			/*for(Long swid : fractionOnHandler.keySet()) {
			querySwitchHandlerCPUTime(swid);
			totalCpuTimeOnHandler += querySwitchHandlerCPUTime(swid);
		}*/
			//assume total cpu time can be viewed as cpu on handler + cpu on non-handler
			long totalCpuTimeOnNonHandler = totalCpuTimeElapsed - totalCpuTimeOnHandler;
			currentNonHandlerCpuTime = totalCpuTimeOnNonHandler;
			//assume the time spent is evenly distributed
			Set<Long> allDpids = floodlightProvider.getAllSwitchDpids();
			long cpuTimeOnNonHandlerEach = totalCpuTimeOnNonHandler/allDpids.size();
			
			//compute total system since last call
			totalSysElapsedTime = System.nanoTime() - totalSystemTimeLastTime;
			//now combine the two fractions and use the sum as total fraction
			for(Long swid : currentAllSwitchOnHandlerCpuTime.keySet()) {
				long elapsedOnHandler = currentAllSwitchOnHandlerCpuTime.get(swid);
				long elapsedTotal = elapsedOnHandler + cpuTimeOnNonHandlerEach;
				
				double fraction = (double)elapsedTotal/(double)totalSysElapsedTime;
				
				double fractionOnHandler = (double)elapsedOnHandler/(double)totalSysElapsedTime;
				double fractionNonHandler = (double)cpuTimeOnNonHandlerEach/(double)totalSysElapsedTime;
				//allFractions.put(swid, fractionOnHandler);
				allFractions.put(swid, fractionOnHandler);
				/*String s = "==========> sw " + swid + 
						" total: " + fraction + 
						" h-only is " + fractionOnHandler + 
						"(" + elapsedOnHandler + "/" + totalSysElapsedTime + ")" +
						" non-h-only is " + fractionNonHandler +
						"(" + cpuTimeOnNonHandlerEach + "/" + totalSysElapsedTime;*/
				String s = "handler util of sw " + swid +
						" is:" + fraction;
				logger.info(s);
			}
			//for those that do not have handler cpu time recorded, and average non-handler cpu time to it
			for(Long swid : allDpids) {
				if(!allFractions.containsKey(swid)) {
					double fraction = (double)cpuTimeOnNonHandlerEach/(double)totalSysElapsedTime;
					//allFractions.put(swid, fraction);
					allFractions.put(swid, new Double(0));
					logger.info("no handler util recorded in this interval for sw " + swid);
				}
			}
			double controllerFraction = (double)totalCpuTimeElapsed/(double)totalSysElapsedTime;
			logger.info("for this controller, total cpu:" + totalCpuTimeElapsed + 
					" total non-handler time " + currentNonHandlerCpuTime + 
					" and total system time " + totalSysElapsedTime + " fraction " + controllerFraction);
		} else {
			//in this case, there is no event handler part at all!(maybe because no packet_in thing coming
			//in this period)
			//in this case, we think of all cpu time as non handler time
			currentNonHandlerCpuTime = totalCpuTimeElapsed;
			totalSysElapsedTime = System.nanoTime() - totalSystemTimeLastTime;
			double controllerFraction = (double)totalCpuTimeElapsed/(double)totalSysElapsedTime;
			logger.info("for this controller, total cpu:" + totalCpuTimeElapsed + 
					" total non-handler time " + currentNonHandlerCpuTime + 
					" and total system time " + totalSysElapsedTime + " fraction " + controllerFraction);
		}
		
		info.setHandlerFraction(allFractions);
		info.setNonHandlerTime(currentNonHandlerCpuTime);
		info.setTotalCpuTimeElapsed(totalCpuTimeElapsed);
		info.setTotalSysTimeElapsed(totalSysElapsedTime);
		ArrayList<Long> allSwitch = new ArrayList<Long>(floodlightProvider.getAllSwitchDpids());
		info.setAllSwitch(allSwitch);
		return info;
	}
	
	public HashMap<Long, Double> getAllFractionAndRefresh() {
		//look at how much total cpu time has elapsed 
		//since last call for all threads, denote by A
		//for each switch, compute its total cpu time 
		//fraction in this period
		//total fraction refers to (handler fraction + non-handler average time)/total cpu time
		//put this info in a map
		//clear all current data
		//reset total threads time to current total all threads 
		//cpu time
		//return the map
		if(threadCpuTime.size() == 0 || totalAllSwitchOnHandlerCpuTime.size() == 0 ||
				totalSystemTimeLastTime == 0) {
			//no info at this point, meaning no fraction can be computed,
			//record some state, return
			threadCpuTime = getAllCurrentThreadCpuTime();
			
//			totalCpuTimeLastTime = 0;//getCurrentAllThreadTime();
//			for(long time : threadCpuTime.values()) {
//				totalCpuTimeLastTime += time;
//			}
			totalAllSwitchOnHandlerCpuTime = getAllSwitchHandlerCPUTime();
			totalSystemTimeLastTime = System.nanoTime();
			return null;
		}
		
		FloodlightMeasurementInfo info = getInfo();
		
		//reset some states
		totalAllSwitchOnHandlerCpuTime = getAllSwitchHandlerCPUTime();
		totalSystemTimeLastTime = System.nanoTime();
		currentNonHandlerCpuTime = 0;
		clearCumulativeTimes();
		timesMap.clear();
		return info.getHandlerFraction();
	}
	
}
