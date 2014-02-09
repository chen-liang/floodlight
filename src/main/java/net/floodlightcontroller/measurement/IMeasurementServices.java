package net.floodlightcontroller.measurement;

import org.openflow.protocol.OFType;

import net.floodlightcontroller.core.module.IFloodlightService;

public interface IMeasurementServices extends IFloodlightService {
	public void recordStartSystemTime(Long swid, OFType type, String listenerName, long time);
	
	public void recordStartCPUTime(Long swid, OFType type, String listenerName, long time);
	
	public void recordEndSystemTime(Long swid, OFType type, String listenerName, long time);
	
	public void recordEndCPUTime(Long swid, OFType type, String listenerName, long time);
	
	public void recordStart(Long swid, OFType type, String listenerName, long cpustart, long sysstart);
	
	public void recordEnd(Long swid, OFType type, String listenerName, long cpuend, long sysend);
	
}
