// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.storage.upload;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.ejb.Local;
import javax.inject.Inject;
import javax.naming.ConfigurationException;

import org.apache.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.agent.AgentManager;
import com.cloud.agent.Listener;
import com.cloud.agent.api.Command;
import com.cloud.agent.api.storage.CreateEntityDownloadURLCommand;
import com.cloud.agent.api.storage.DeleteEntityDownloadURLCommand;
import com.cloud.agent.api.storage.UploadCommand;
import com.cloud.agent.api.storage.UploadProgressCommand.RequestType;
import com.cloud.agent.manager.Commands;
import com.cloud.api.ApiDBUtils;
import com.cloud.async.AsyncJobManager;
import com.cloud.configuration.dao.ConfigurationDao;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.resource.ResourceManager;
import com.cloud.storage.Storage.ImageFormat;
import com.cloud.storage.Upload;
import com.cloud.storage.Upload.Mode;
import com.cloud.storage.Upload.Status;
import com.cloud.storage.Upload.Type;
import com.cloud.storage.UploadVO;
import com.cloud.storage.VMTemplateHostVO;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.UploadDao;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VMTemplateHostDao;
import com.cloud.storage.secondary.SecondaryStorageVmManager;
import com.cloud.utils.NumbersUtil;
import com.cloud.utils.concurrency.NamedThreadFactory;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.SecondaryStorageVm;
import com.cloud.vm.SecondaryStorageVmVO;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.dao.SecondaryStorageVmDao;

/**
 * Monitors the progress of upload.
 */
@Component
@Local(value={UploadMonitor.class})
public class UploadMonitorImpl implements UploadMonitor {

	static final Logger s_logger = Logger.getLogger(UploadMonitorImpl.class);
	
    @Inject 
    VMTemplateHostDao _vmTemplateHostDao;
    @Inject 
    UploadDao _uploadDao;
    @Inject
    SecondaryStorageVmDao _secStorageVmDao;

    
    @Inject
    HostDao _serverDao = null;    
    @Inject
    VMTemplateDao _templateDao =  null;
    @Inject
	private AgentManager _agentMgr;
    @Inject
    ConfigurationDao _configDao;
    @Inject
    ResourceManager _resourceMgr;
    @Inject
    SecondaryStorageVmManager _ssvmMgr;

	private String _name;
	private Boolean _sslCopy = new Boolean(false);
    private ScheduledExecutorService _executor = null;

	Timer _timer;
	int _cleanupInterval;
	int _urlExpirationInterval;

	final Map<UploadVO, UploadListener> _listenerMap = new ConcurrentHashMap<UploadVO, UploadListener>();

	
	@Override
	public void cancelAllUploads(Long templateId) {
		// TODO

	}	
	
	@Override
	public boolean isTypeUploadInProgress(Long typeId, Type type) {
		List<UploadVO> uploadsInProgress =
			_uploadDao.listByTypeUploadStatus(typeId, type, UploadVO.Status.UPLOAD_IN_PROGRESS);
		
		if(uploadsInProgress.size() > 0) {
            return true;
        } else if (type == Type.VOLUME && _uploadDao.listByTypeUploadStatus(typeId, type, UploadVO.Status.COPY_IN_PROGRESS).size() > 0){
		    return true;
		}
		return false;
		
	}
	
	@Override
	public UploadVO createNewUploadEntry(Long hostId, Long typeId, UploadVO.Status  uploadState,
	                                        Type  type, String uploadUrl, Upload.Mode mode){
	       
        UploadVO uploadObj = new UploadVO(hostId, typeId, new Date(), 
                                          uploadState, type, uploadUrl, mode);
        _uploadDao.persist(uploadObj);
        
        return uploadObj;
	    
	}
	
	@Override
	public void extractVolume(UploadVO uploadVolumeObj, HostVO sserver, VolumeVO volume, String url, Long dataCenterId, String installPath, long eventId, long asyncJobId, AsyncJobManager asyncMgr){				
						
		uploadVolumeObj.setUploadState(Upload.Status.NOT_UPLOADED);
		_uploadDao.update(uploadVolumeObj.getId(), uploadVolumeObj);
				
	    start();		
		UploadCommand ucmd = new UploadCommand(url, volume.getId(), volume.getSize(), installPath, Type.VOLUME);
		UploadListener ul = new UploadListener(sserver, _timer, _uploadDao, uploadVolumeObj, this, ucmd, volume.getAccountId(), volume.getName(), Type.VOLUME, eventId, asyncJobId, asyncMgr);
		_listenerMap.put(uploadVolumeObj, ul);

		try {
	        send(sserver.getId(), ucmd, ul);
        } catch (AgentUnavailableException e) {
			s_logger.warn("Unable to start upload of volume " + volume.getName() + " from " + sserver.getName() + " to " +url, e);
			ul.setDisconnected();
			ul.scheduleStatusCheck(RequestType.GET_OR_RESTART);
        }		
	}

	@Override
	public Long extractTemplate( VMTemplateVO template, String url,
			VMTemplateHostVO vmTemplateHost,Long dataCenterId, long eventId, long asyncJobId, AsyncJobManager asyncMgr){

		Type type = (template.getFormat() == ImageFormat.ISO) ? Type.ISO : Type.TEMPLATE ;
				
		List<HostVO> storageServers = _resourceMgr.listAllHostsInOneZoneByType(Host.Type.SecondaryStorage, dataCenterId);
		HostVO sserver = storageServers.get(0);			
		
		UploadVO uploadTemplateObj = new UploadVO(sserver.getId(), template.getId(), new Date(), 
													Upload.Status.NOT_UPLOADED, type, url, Mode.FTP_UPLOAD);
		_uploadDao.persist(uploadTemplateObj);        		               
        		
		if(vmTemplateHost != null) {
		    start();
			UploadCommand ucmd = new UploadCommand(template, url, vmTemplateHost.getInstallPath(), vmTemplateHost.getSize());	
			UploadListener ul = new UploadListener(sserver, _timer, _uploadDao, uploadTemplateObj, this, ucmd, template.getAccountId(), template.getName(), type, eventId, asyncJobId, asyncMgr);			
			_listenerMap.put(uploadTemplateObj, ul);

			try {
	            send(sserver.getId(), ucmd, ul);
            } catch (AgentUnavailableException e) {
				s_logger.warn("Unable to start upload of " + template.getUniqueName() + " from " + sserver.getName() + " to " +url, e);
				ul.setDisconnected();
				ul.scheduleStatusCheck(RequestType.GET_OR_RESTART);
            }
			return uploadTemplateObj.getId();
		}		
		return null;		
	}	
	
	@Override
	public UploadVO createEntityDownloadURL(VMTemplateVO template, VMTemplateHostVO vmTemplateHost, Long dataCenterId, long eventId) {
	    
	    String errorString = "";
	    boolean success = false;
	    Host secStorage = ApiDBUtils.findHostById(vmTemplateHost.getHostId());	    
	    Type type = (template.getFormat() == ImageFormat.ISO) ? Type.ISO : Type.TEMPLATE ;
	    
        //Check if ssvm is up
        HostVO ssvm = _ssvmMgr.pickSsvmHost(ApiDBUtils.findHostById(vmTemplateHost.getHostId()));
        if( ssvm == null ) {
            throw new CloudRuntimeException("There is no secondary storage VM for secondary storage host " + secStorage.getId());
        }
	    
	    //Check if it already exists.
	    List<UploadVO> extractURLList = _uploadDao.listByTypeUploadStatus(template.getId(), type, UploadVO.Status.DOWNLOAD_URL_CREATED);	    
	    if (extractURLList.size() > 0) {
            return extractURLList.get(0);
        }
	    
	    // It doesn't exist so create a DB entry.	    
	    UploadVO uploadTemplateObj = new UploadVO(vmTemplateHost.getHostId(), template.getId(), new Date(), 
	                                                Status.DOWNLOAD_URL_NOT_CREATED, 0, type, Mode.HTTP_DOWNLOAD); 
	    uploadTemplateObj.setInstallPath(vmTemplateHost.getInstallPath());	                                                
	    _uploadDao.persist(uploadTemplateObj);
	    try{
    	    // Create Symlink at ssvm
	    	String path = vmTemplateHost.getInstallPath();
	    	String uuid = UUID.randomUUID().toString() + "." + template.getFormat().getFileExtension(); // adding "." + vhd/ova... etc.
	    	CreateEntityDownloadURLCommand cmd = new CreateEntityDownloadURLCommand(secStorage.getParent(), path, uuid);
    	    try {
	            send(ssvm.getId(), cmd, null);
            } catch (AgentUnavailableException e) {
    	        errorString = "Unable to create a link for " +type+ " id:"+template.getId() + "," + e.getMessage();
                s_logger.error(errorString, e);
                throw new CloudRuntimeException(errorString);
            }

    	    //Construct actual URL locally now that the symlink exists at SSVM
            String extractURL = generateCopyUrl(ssvm.getPublicIpAddress(), uuid);
            UploadVO vo = _uploadDao.createForUpdate();
            vo.setLastUpdated(new Date());
            vo.setUploadUrl(extractURL);
            vo.setUploadState(Status.DOWNLOAD_URL_CREATED);
            _uploadDao.update(uploadTemplateObj.getId(), vo);
            success = true;
            return _uploadDao.findById(uploadTemplateObj.getId(), true);        
	    }finally{
           if(!success){
                UploadVO uploadJob = _uploadDao.createForUpdate(uploadTemplateObj.getId());
                uploadJob.setLastUpdated(new Date());
                uploadJob.setErrorString(errorString);
                uploadJob.setUploadState(Status.ERROR);
                _uploadDao.update(uploadTemplateObj.getId(), uploadJob);
            }
	    }
	    
	}
	
	@Override
    public void createVolumeDownloadURL(Long entityId, String path, Type type, Long dataCenterId, Long uploadId) {
        
	    String errorString = "";
	    boolean success = false;
	    try{
            List<HostVO> storageServers = _resourceMgr.listAllHostsInOneZoneByType(Host.Type.SecondaryStorage, dataCenterId);
            if(storageServers == null ){
                errorString = "No Storage Server found at the datacenter - " +dataCenterId;
                throw new CloudRuntimeException(errorString);   
            }                    
            
            // Update DB for state = DOWNLOAD_URL_NOT_CREATED.        
            UploadVO uploadJob = _uploadDao.createForUpdate(uploadId);
            uploadJob.setUploadState(Status.DOWNLOAD_URL_NOT_CREATED);
            uploadJob.setLastUpdated(new Date());
            _uploadDao.update(uploadJob.getId(), uploadJob);

            // Create Symlink at ssvm
            String uuid = UUID.randomUUID().toString() + path.substring(path.length() - 4) ; // last 4 characters of the path specify the format like .vhd
            HostVO secStorage = ApiDBUtils.findHostById(ApiDBUtils.findUploadById(uploadId).getHostId());
            HostVO ssvm = _ssvmMgr.pickSsvmHost(secStorage);
            if( ssvm == null ) {
            	errorString = "There is no secondary storage VM for secondary storage host " + secStorage.getName();
            	throw new CloudRuntimeException(errorString);
            }
            
            CreateEntityDownloadURLCommand cmd = new CreateEntityDownloadURLCommand(secStorage.getParent(), path, uuid);
            try {
	            send(ssvm.getId(), cmd, null);
            } catch (AgentUnavailableException e) {
                errorString = "Unable to create a link for " +type+ " id:"+entityId + "," + e.getMessage();
                s_logger.warn(errorString, e);
                throw new CloudRuntimeException(errorString);
            }

            List<SecondaryStorageVmVO> ssVms = _secStorageVmDao.getSecStorageVmListInStates(SecondaryStorageVm.Role.templateProcessor, dataCenterId, State.Running);
    	    if (ssVms.size() > 0) {
                SecondaryStorageVmVO ssVm = ssVms.get(0);
                if (ssVm.getPublicIpAddress() == null) {
                    errorString = "A running secondary storage vm has a null public ip?";
                    s_logger.error(errorString);
                    throw new CloudRuntimeException(errorString);
                }
                //Construct actual URL locally now that the symlink exists at SSVM
                String extractURL = generateCopyUrl(ssVm.getPublicIpAddress(), uuid);
                UploadVO vo = _uploadDao.createForUpdate();
                vo.setLastUpdated(new Date());
                vo.setUploadUrl(extractURL);
                vo.setUploadState(Status.DOWNLOAD_URL_CREATED);
                _uploadDao.update(uploadId, vo);
                success = true;
                return;
            }
            errorString = "Couldnt find a running SSVM in the zone" + dataCenterId+ ". Couldnt create the extraction URL.";
            throw new CloudRuntimeException(errorString);
	    }finally{
	        if(!success){
	            UploadVO uploadJob = _uploadDao.createForUpdate(uploadId);
	            uploadJob.setLastUpdated(new Date());
	            uploadJob.setErrorString(errorString);
	            uploadJob.setUploadState(Status.ERROR);
	            _uploadDao.update(uploadId, uploadJob);
	        }
	    }
    }
	
	   private String generateCopyUrl(String ipAddress, String uuid){
	        String hostname = ipAddress;
	        String scheme = "http";
	        if (_sslCopy) {
	            hostname = ipAddress.replace(".", "-");
	            hostname = hostname + ".realhostip.com";
	            scheme = "https";
	        }
	        return scheme + "://" + hostname + "/userdata/" + uuid; 
	    }
	


	public void send(Long hostId, Command cmd, Listener listener) throws AgentUnavailableException {
		_agentMgr.send(hostId, new Commands(cmd), listener);
	}

	@Override
	public boolean configure(String name, Map<String, Object> params)
			throws ConfigurationException {
		_name = name;
        final Map<String, String> configs = _configDao.getConfiguration("ManagementServer", params);
        _sslCopy = Boolean.parseBoolean(configs.get("secstorage.encrypt.copy"));
        
        String cert = configs.get("secstorage.secure.copy.cert");
        if ("realhostip.com".equalsIgnoreCase(cert)) {
        	s_logger.warn("Only realhostip.com ssl cert is supported, ignoring self-signed and other certs");
        }        
        
        _agentMgr.registerForHostEvents(new UploadListener(this), true, false, false);
        String cleanupInterval = configs.get("extract.url.cleanup.interval");
        _cleanupInterval = NumbersUtil.parseInt(cleanupInterval, 7200);
        
        String urlExpirationInterval = configs.get("extract.url.expiration.interval");
        _urlExpirationInterval = NumbersUtil.parseInt(urlExpirationInterval, 14400);
        
        String workers = (String)params.get("expunge.workers");
        int wrks = NumbersUtil.parseInt(workers, 1);
        _executor = Executors.newScheduledThreadPool(wrks, new NamedThreadFactory("UploadMonitor-Scavenger"));
		return true;
	}

	@Override
	public String getName() {
		return _name;
	}

	@Override
	public boolean start() {	    
	    _executor.scheduleWithFixedDelay(new StorageGarbageCollector(), _cleanupInterval, _cleanupInterval, TimeUnit.SECONDS);
		_timer = new Timer();
		return true;
	}

	@Override
	public boolean stop() {		
		return true;
	}
	
	public void handleUploadEvent(HostVO host, Long accountId, String typeName, Type type, Long uploadId, com.cloud.storage.Upload.Status reason, long eventId) {
		
		if ((reason == Upload.Status.UPLOADED) || (reason==Upload.Status.ABANDONED)){
			UploadVO uploadObj = new UploadVO(uploadId);
			UploadListener oldListener = _listenerMap.get(uploadObj);
			if (oldListener != null) {
				_listenerMap.remove(uploadObj);
			}
		}

	}
	
	@Override
	public void handleUploadSync(long sserverId) {
	    
	    HostVO storageHost = _serverDao.findById(sserverId);
        if (storageHost == null) {
            s_logger.warn("Huh? Agent id " + sserverId + " does not correspond to a row in hosts table?");
            return;
        }
        s_logger.debug("Handling upload sserverId " +sserverId);
        List<UploadVO> uploadsInProgress = new ArrayList<UploadVO>();
        uploadsInProgress.addAll(_uploadDao.listByHostAndUploadStatus(sserverId, UploadVO.Status.UPLOAD_IN_PROGRESS));
        uploadsInProgress.addAll(_uploadDao.listByHostAndUploadStatus(sserverId, UploadVO.Status.COPY_IN_PROGRESS));
        if (uploadsInProgress.size() > 0){
            for (UploadVO uploadJob : uploadsInProgress){
                uploadJob.setUploadState(UploadVO.Status.UPLOAD_ERROR);
                uploadJob.setErrorString("Could not complete the upload.");
                uploadJob.setLastUpdated(new Date());
                _uploadDao.update(uploadJob.getId(), uploadJob);
            }
            
        }
	        

	}				

    protected class StorageGarbageCollector implements Runnable {

        public StorageGarbageCollector() {
        }

        @Override
        public void run() {
            try {
                GlobalLock scanLock = GlobalLock.getInternLock("uploadmonitor.storageGC");
                try {
                    if (scanLock.lock(3)) {
                        try {
                            cleanupStorage();
                        } finally {
                            scanLock.unlock();
                        }
                    }
                } finally {
                    scanLock.releaseRef();
                }

            } catch (Exception e) {
                s_logger.error("Caught the following Exception", e);
            }
        }
    }
    
    
    private long getTimeDiff(Date date){
        Calendar currentDateCalendar = Calendar.getInstance();
        Calendar givenDateCalendar = Calendar.getInstance();
        givenDateCalendar.setTime(date);
        
        return (currentDateCalendar.getTimeInMillis() - givenDateCalendar.getTimeInMillis() )/1000;  
    }
    
    public void cleanupStorage() {

        final int EXTRACT_URL_LIFE_LIMIT_IN_SECONDS = _urlExpirationInterval;
        List<UploadVO> extractJobs= _uploadDao.listByModeAndStatus(Mode.HTTP_DOWNLOAD, Status.DOWNLOAD_URL_CREATED);
        
        for (UploadVO extractJob : extractJobs){
            if( getTimeDiff(extractJob.getLastUpdated()) > EXTRACT_URL_LIFE_LIMIT_IN_SECONDS ){                           
                String path = extractJob.getInstallPath();
                HostVO secStorage = ApiDBUtils.findHostById(extractJob.getHostId());
                
                // Would delete the symlink for the Type and if Type == VOLUME then also the volume
                DeleteEntityDownloadURLCommand cmd = new DeleteEntityDownloadURLCommand(path, extractJob.getType(),extractJob.getUploadUrl(), secStorage.getParent());
                HostVO ssvm = _ssvmMgr.pickSsvmHost(secStorage);
                if( ssvm == null ) {
                	s_logger.warn("UploadMonitor cleanup: There is no secondary storage VM for secondary storage host " + extractJob.getHostId());
                	continue; //TODO: why continue? why not break?
                }
                if (s_logger.isDebugEnabled()) {
                	s_logger.debug("UploadMonitor cleanup: Sending deletion of extract URL "+ extractJob.getUploadUrl() + " to ssvm " + ssvm.getId());
                }
                try {
                    send(ssvm.getId(), cmd, null); //TODO: how do you know if it was successful?
                    _uploadDao.remove(extractJob.getId());
                } catch (AgentUnavailableException e) {
                	s_logger.warn("UploadMonitor cleanup: Unable to delete the link for " + extractJob.getType()+ " id=" + extractJob.getTypeId()+ " url="+ extractJob.getUploadUrl() + " on ssvm " + ssvm.getId(), e);
                }
            }
        }
                
    }

}
