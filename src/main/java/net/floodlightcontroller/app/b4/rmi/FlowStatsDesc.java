package net.floodlightcontroller.app.b4.rmi;

import java.io.Serializable;

import org.openflow.protocol.OFMatch;

public class FlowStatsDesc implements Serializable {

	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	Long byteCount;
	OFMatch match;
	
	public FlowStatsDesc() {
		byteCount = new Long(0);
		match = null;
	}
	
	public FlowStatsDesc(Long count, OFMatch omatch) {
		byteCount = count;
		match = omatch;
	}
	
	public void setCount(Long count) {
		byteCount = count;
	}
	
	public long getCount() {
		return byteCount;
	}
	
	public void setMatch(OFMatch omatch) {
		match = omatch;
	}
	
	public OFMatch getMatch() {
		return match;
	}
	
	@Override
	public String toString() {
		return match.toString() + " with count:" + byteCount;
	}

}
