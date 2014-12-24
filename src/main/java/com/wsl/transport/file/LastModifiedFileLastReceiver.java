package com.wsl.transport.file;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.locks.Lock;

import org.apache.commons.io.comparator.LastModifiedFileComparator;
import org.mule.api.config.MuleProperties;
import org.mule.api.construct.FlowConstruct;
import org.mule.api.endpoint.InboundEndpoint;
import org.mule.api.lifecycle.CreateException;
import org.mule.api.lifecycle.InitialisationException;
import org.mule.api.store.ObjectAlreadyExistsException;
import org.mule.api.store.ObjectStore;
import org.mule.api.store.ObjectStoreManager;
import org.mule.api.transport.Connector;
import org.mule.transport.file.FileMessageReceiver;
import org.mule.util.FileUtils;
import org.mule.util.lock.LockFactory;

public class LastModifiedFileLastReceiver extends FileMessageReceiver {

	private LockFactory lockFactory;
	private ObjectStore<String> filesBeingProcessingObjectStore;
	private static final List<File> NO_FILES = new ArrayList<File>();
	private String readDir;
	private File readDirectory;

	public LastModifiedFileLastReceiver(Connector connector, FlowConstruct flowConstruct, InboundEndpoint endpoint,
			String readDir, String moveDir, String moveToPattern, long frequency) throws CreateException {
		super(connector, flowConstruct, endpoint, readDir, moveDir, moveToPattern, frequency);
		this.readDir = readDir;
	}

	@Override
	protected void doInitialise() throws InitialisationException {
		super.doInitialise();
		this.lockFactory = getEndpoint().getMuleContext().getLockFactory();
		ObjectStoreManager objectStoreManager = getEndpoint().getMuleContext().getRegistry()
				.get(MuleProperties.OBJECT_STORE_MANAGER);
		filesBeingProcessingObjectStore = objectStoreManager.getObjectStore(getEndpoint().getName(), false, 1000,
				60000, 20000);
	}

	@Override
	protected void doConnect() throws Exception {
		super.doConnect();
		readDirectory = FileUtils.openDirectory(readDir);

	}

	@Override
	public void poll() {
		try {
			List<File> files = new ArrayList<File>();
			this.basicListFiles(readDirectory, files);
			files = files.isEmpty() ? NO_FILES : files;

			if (logger.isDebugEnabled()) {
				logger.debug("Files: " + files.toString());
			}

			// to sort file in ascending order
			 Collections.sort(files, LastModifiedFileComparator.LASTMODIFIED_COMPARATOR);

			for (File file : files) {
				if (getLifecycleState().isStopping()) {
					break;
				}
				// don't process directories
				if (file.isFile()) {
					Lock fileLock = lockFactory.createLock(file.getName());
					if (fileLock.tryLock()) {
						try {
							String fileAbsolutePath = file.getAbsolutePath();
							try {
								filesBeingProcessingObjectStore.store(fileAbsolutePath, fileAbsolutePath);

								if (logger.isDebugEnabled()) {
									logger.debug(String.format("Flag for '%s' stored successfully.", fileAbsolutePath));
								}
							} catch (ObjectAlreadyExistsException e) {
								if (logger.isDebugEnabled()) {
									logger.debug(String.format("Flag for '%s' being processed is on. Skipping file.",
											fileAbsolutePath));
								}
								continue;
							}
							if (file.exists()) {
								processFile(file);
							}
						} finally {
							fileLock.unlock();
						}
					}
				}
			}
		} catch (Exception e) {
			getEndpoint().getMuleContext().getExceptionListener().handleException(e);
		}
	}
}
