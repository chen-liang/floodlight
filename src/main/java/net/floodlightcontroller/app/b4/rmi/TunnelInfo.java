package net.floodlightcontroller.app.b4.rmi;

import java.io.Serializable;
import java.util.LinkedList;

public class TunnelInfo implements Serializable {
	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	String tid;
	LinkedList<Long> path;
	Long currBw;
	
	public TunnelInfo(String tid, LinkedList<Long> path, Long bw) {
		this.tid = tid;
		this.path = new LinkedList<Long>();
		this.path.addAll(path);
		this.currBw = bw;
	}
	
	public String getTid() {
		return this.tid;
	}
	
	public LinkedList<Long> getPath() {
		return this.path;
	}
	
	public Long getBw() {
		return currBw;
	}
	
}
