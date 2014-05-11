package net.floodlightcontroller.measurement;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;

public class FloodlightMeasurementInfo implements Serializable {
	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;

	HashMap<Long, Double> handlerFraction;
	
	ArrayList<Long> allSwitches;
	
	long totalNonHandlerTime;
	
	long totalCPUTimeElapsed;
	
	long totalSysTimeElapsed;
	
	public FloodlightMeasurementInfo() {
		handlerFraction = new HashMap<Long, Double>();
		allSwitches = new ArrayList<Long>();
		totalNonHandlerTime = 0;
		totalCPUTimeElapsed = 0;
		totalSysTimeElapsed = 0;
	}
	
	public void setHandlerFraction(HashMap<Long, Double> handlerFraction) {
		this.handlerFraction = handlerFraction;
	}
	
	public HashMap<Long, Double> getHandlerFraction() {
		return this.handlerFraction;
	}
	
	public void setAllSwitch(ArrayList<Long> allSwitch) {
		this.allSwitches = allSwitch;
	}
	
	public ArrayList<Long> getAllSwitch() {
		return this.allSwitches;
	}
	
	public void setNonHandlerTime(long time) {
		this.totalNonHandlerTime = time;
	}
	
	public long getNonHandlerTime() {
		return this.totalNonHandlerTime;
	}
	
	public void setTotalCpuTimeElapsed(long time) {
		this.totalCPUTimeElapsed = time;
	}
	
	public long getTotalCpuTimeElapsed() {
		return this.totalCPUTimeElapsed;
	}
	
	public void setTotalSysTimeElapsed(long time) {
		this.totalSysTimeElapsed = time;
	}
	
	public long getTotalSysTimeElapsed() {
		return this.totalSysTimeElapsed;
	}
}
