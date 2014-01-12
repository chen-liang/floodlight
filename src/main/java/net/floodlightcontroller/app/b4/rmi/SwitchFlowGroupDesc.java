package net.floodlightcontroller.app.b4.rmi;

import java.io.Serializable;
import java.util.LinkedList;

import org.openflow.protocol.OFMatch;

public class SwitchFlowGroupDesc implements Serializable {
	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	Long srcSwid;
	Long dstSwid;
	Long bw;
	//use src and dst to identify a flowgroup, 
	//local use srcsw and dstsw and the OFMatch obj to
	//compute allocation
	Long fgSrcSwid;
	Long fgDstSwid;
	//LinkedList<OFMatch> matches;
	
	public SwitchFlowGroupDesc(Long src, Long dst, Long bw, 
			Long fgSrcSwid, Long fgDstSwid, LinkedList<OFMatch> matches) {
		this.srcSwid = src;
		this.dstSwid = dst;
		this.bw = bw;
		this.fgSrcSwid = fgSrcSwid;
		this.fgDstSwid = fgDstSwid;
		//this.matches = matches;
	}
	
	@Override
	public String toString() {
		String s = "";
		s += "[src:" + srcSwid 
				+ " dst:" + dstSwid 
				+ " bw:" + bw 
				+ " fgsrcid:" + fgSrcSwid
				+ " fgdstid:" + fgDstSwid + "]";
		
		return s;
	}
	
	public void setSrc(Long swid) {
		this.srcSwid = swid;
	}
	
	public void setDst(Long swid) {
		this.dstSwid = swid;
	}
	
	public void setBw(Long bandwidth) {
		this.bw = bandwidth;
	}
	
	public void setFGSrcSwid(Long fgSrcSwid) {
		this.fgSrcSwid = fgSrcSwid;
	}
	
	public void setFGDstSwid(Long fgDstSwid) {
		this.fgDstSwid = fgDstSwid;
	}
	
//	public void setMatch(LinkedList<OFMatch> matches) {
//		this.matches = matches;
//	}
	
	public Long getSrc() {
		return this.srcSwid;
	}
	
	public Long getDst() {
		return this.dstSwid;
	}
	
	public Long getBw() {
		return this.bw;
	}
	
	public Long getFgSrcSwid() {
		return this.fgSrcSwid;
	}
	
	public Long getFgDstSwid() {
		return this.fgDstSwid;
	}
//	public LinkedList<OFMatch> getMatches() {
//		return this.matches;
//	}
}
