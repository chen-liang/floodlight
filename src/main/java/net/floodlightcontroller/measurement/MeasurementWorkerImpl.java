package net.floodlightcontroller.measurement;

import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.HashMap;

public class MeasurementWorkerImpl extends UnicastRemoteObject implements MeasurementWorker {

	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	Measurement measurementRef;
	
	public MeasurementWorkerImpl(Measurement mref) throws RemoteException {
		super();
		measurementRef = mref;
	}

	@Override
	public HashMap<Long, Double> getAllFractionAndRefresh()
			throws RemoteException {
		return measurementRef.getAllFractionAndRefresh();
	}
}
