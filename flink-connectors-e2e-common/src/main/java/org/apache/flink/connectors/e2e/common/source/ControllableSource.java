package org.apache.flink.connectors.e2e.common.source;

import org.apache.flink.api.common.functions.AbstractRichFunction;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.runtime.state.FunctionInitializationContext;
import org.apache.flink.runtime.state.FunctionSnapshotContext;
import org.apache.flink.streaming.api.checkpoint.CheckpointedFunction;
import org.apache.flink.streaming.api.functions.source.SourceFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Serializable;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

public class ControllableSource
		extends AbstractRichFunction
		implements SourceFunction<String>, CheckpointedFunction, SourceControlRpc {

	private static final Logger LOG = LoggerFactory.getLogger(ControllableSource.class);

	public static final String RMI_PROP_REGISTRY_FILTER = "sun.rmi.registry.registryFilter";
	public static final String RMI_PROP_SERVER_HOSTNAME = "java.rmi.server.hostname";
	public static final String RMI_REGISTRY_NAME = "SourceControl";
	public static final String RMI_HOSTNAME = "127.0.0.1";
	public static final int RMI_PORT = 15213;

	private Registry rmiRegistry;

	private volatile boolean isRunning = true;
	private volatile boolean isStepping = true;

	private final SyncLock syncLock = new SyncLock();

	private final File recordingFile;
	private BufferedWriter br;

	private AtomicInteger numElementsToEmit;

	private final String END_MARK;

	public ControllableSource(String recordingFilePath, String endMark) {
		recordingFile = new File(recordingFilePath);
		END_MARK = endMark;
	}

	/*------------------- Checkpoint related---------------------*/
	@Override
	public void snapshotState(FunctionSnapshotContext context) throws Exception {

	}

	@Override
	public void initializeState(FunctionInitializationContext context) throws Exception {

	}


	/*-------------------- Rich function related -----------------*/
	@Override
	public void open(Configuration parameters) throws Exception {
		// Setup Java RMI
		java.lang.System.setProperty(RMI_PROP_REGISTRY_FILTER, "java.**;org.apache.flink.**");
		java.lang.System.setProperty(RMI_PROP_SERVER_HOSTNAME, RMI_HOSTNAME);
		UnicastRemoteObject.exportObject(this, RMI_PORT);
		rmiRegistry = LocateRegistry.createRegistry(RMI_PORT);
		rmiRegistry.bind(RMI_REGISTRY_NAME, this);

		// Setup recording file
		br = new BufferedWriter(new FileWriter(recordingFile));

		// Setup record counter
		numElementsToEmit = new AtomicInteger(0);
	}

	@Override
	public void run(SourceContext<String> ctx) throws Exception {

		// Main loop
		while (isRunning) {
			
			if (isStepping) {

				while (numElementsToEmit.get() > 0) {
					emitAndRecordElement(ctx, generateRandomString(20));
					numElementsToEmit.decrementAndGet();
				}
				// Double check the status before sleeping
				if (isStepping) {
					synchronized (syncLock) {
						syncLock.wait();
					}
				}

			} else {

				LOG.info("###### IS RUNNING....");
				emitAndRecordElement(ctx, generateRandomString(10));
			}
		}

		// Ready to finish the job
		// Finish leftover
		while (numElementsToEmit.get() > 0) {
			emitAndRecordElement(ctx, generateRandomString(20));
			numElementsToEmit.decrementAndGet();
		}
		
		// Emit end mark before exit
		ctx.collect(END_MARK);
	}

	@Override
	public void cancel() {
		synchronized (syncLock) {
			isRunning = false;
			syncLock.notify();
		}
	}

	@Override
	public void close() throws Exception {
		br.close();
		UnicastRemoteObject.unexportObject(rmiRegistry, true);
	}

	/*------------------------- Java RMI related ----------------------*/
	@Override
	public void pause() throws RemoteException {
		isStepping = true;
	}

	@Override
	public void next() throws RemoteException {
		// if main thread is running, just ignore the request
		if (!isStepping) {
			return;
		}
		numElementsToEmit.incrementAndGet();
		// Main thread maybe sleeping, wake it up
		synchronized (syncLock) {
			syncLock.notify();
		}
		LOG.info("###### NEXT!");
	}

	@Override
	public void go() throws RemoteException {
		synchronized (syncLock) {
			isStepping = false;
			syncLock.notify();
		}
		LOG.info("###### GO!");
	}

	@Override
	public void finish() throws RemoteException {
		synchronized (syncLock) {
			isRunning = false;
			syncLock.notify();
		}
	}

	static class SyncLock implements Serializable {
	}

	private String generateRandomString(int length) {
		String alphaNumericString = "ABCDEFGHIJKLMNOPQRSTUVWXYZ" +
				"abcdefghijklmnopqrstuvwxyz" +
				"0123456789";
		StringBuffer sb = new StringBuffer();
		Random random = new Random();
		for (int i = 0; i < length; ++i) {
			sb.append(alphaNumericString.charAt(random.nextInt(alphaNumericString.length())));
		}
		return sb.toString();
	}
	
	private void emitAndRecordElement(SourceContext<String> ctx, String element) throws Exception {
		ctx.collect(element);
		br.append(element).append('\n');
	}
}
