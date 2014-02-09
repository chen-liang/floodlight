package net.floodlightcontroller.measurement;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.HashMap;

public interface MeasurementWorker extends Remote {
	public HashMap<Long, Double> getAllFractionAndRefresh() throws RemoteException;
}
