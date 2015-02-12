package com.kaltura.media.server.managers;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.apache.log4j.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import com.kaltura.client.KalturaApiException;
import com.kaltura.client.KalturaClient;
import com.kaltura.client.KalturaFile;
import com.kaltura.client.KalturaMultiResponse;
import com.kaltura.client.KalturaServiceBase;
import com.kaltura.client.enums.KalturaDVRStatus;
import com.kaltura.client.enums.KalturaMediaServerIndex;
import com.kaltura.client.types.KalturaConversionProfileAssetParams;
import com.kaltura.client.types.KalturaConversionProfileAssetParamsFilter;
import com.kaltura.client.types.KalturaConversionProfileAssetParamsListResponse;
import com.kaltura.client.types.KalturaDataCenterContentResource;
import com.kaltura.client.types.KalturaFlavorAsset;
import com.kaltura.client.types.KalturaFlavorAssetListResponse;
import com.kaltura.client.types.KalturaFlavorParams;
import com.kaltura.client.types.KalturaFlavorParamsListResponse;
import com.kaltura.client.types.KalturaLiveAsset;
import com.kaltura.client.types.KalturaLiveAssetFilter;
import com.kaltura.client.types.KalturaLiveEntry;
import com.kaltura.client.types.KalturaLiveParams;
import com.kaltura.client.types.KalturaServerFileResource;
import com.kaltura.client.types.KalturaUploadToken;
import com.kaltura.client.types.KalturaUploadedFileTokenResource;
import com.kaltura.media.server.KalturaEventsManager;
import com.kaltura.media.server.events.IKalturaEvent;
import com.kaltura.media.server.events.IKalturaEventConsumer;
import com.kaltura.media.server.events.KalturaEventType;
import com.kaltura.media.server.events.KalturaStreamEvent;

abstract public class KalturaLiveManager extends KalturaManager implements ILiveManager, IKalturaEventConsumer {

	protected final static String KALTURA_RECORDED_CHUNCK_MAX_DURATION = "KalturaRecordedChunckMaxDuration";
	protected final static String UPLOAD_XML_SAVE_PATH = "uploadXMLSavePath";
	protected final static String KALTURA_LIVE_STREAM_KEEP_ALIVE_INTERVAL = "KalturaLiveStreamKeepAliveInterval";
	protected final static String KALTURA_LIVE_STREAM_MAX_DVR_WINDOW = "KalturaLiveStreamMaxDvrWindow";
	protected final static String KALTURA_IS_LIVE_REGISTRATION_MIN_BUFFER_TIME = "KalturaIsLiveRegistrationMinBufferTime";
	protected final static String KALTURA_WOWZA_SERVER_WORK_MODE = "KalturaWorkMode";
	
	protected final static long DEFAULT_IS_LIVE_REGISTRATION_MIN_BUFFER_TIME = 5;
	protected final static String KALTURA_WOWZA_SERVER_WORK_MODE_REMOTE = "remote";
	protected final static String KALTURA_WOWZA_SERVER_WORK_MODE_KALTURA = "kaltura";
	
	protected final static String LIVE_STREAM_EXCEEDED_MAX_RECORDED_DURATION = "LIVE_STREAM_EXCEEDED_MAX_RECORDED_DURATION";

	protected static Logger logger = Logger.getLogger(KalturaLiveManager.class);
	
	protected ConcurrentHashMap<String, LiveEntryCache> entries = new ConcurrentHashMap<String, LiveEntryCache>();
	protected ConcurrentHashMap<Integer, KalturaLiveParams> liveAssetParams = new ConcurrentHashMap<Integer, KalturaLiveParams>();
	protected long isLiveRegistrationMinBufferTime = KalturaLiveManager.DEFAULT_IS_LIVE_REGISTRATION_MIN_BUFFER_TIME;

	private Timer setMediaServerTimer;

	protected class LiveEntryCache {
		private KalturaLiveEntry liveEntry;
		private boolean registered = false;
		private KalturaMediaServerIndex index = null;
		private Date registerTime = null;
		private ArrayList<KalturaConversionProfileAssetParams> conversionProfileAssetParams;
		private Map<Integer, KalturaLiveAsset> liveAssets = new HashMap<Integer, KalturaLiveAsset>();
		private Timer timer;
		Set<ILiveEntryReferrer> referrers = new HashSet<ILiveManager.ILiveEntryReferrer>();

		public void addReferrer(ILiveEntryReferrer obj) {
			synchronized (referrers) {
				referrers.add(obj);
				logger.debug(liveEntry.id + " referrer count: "
						+ referrers.size());
			}
		}

		/**
		 * This code is added to prevent a case in which an entry is removed from cache while there are still references to it.
		 */
		public boolean removeReferrer(ILiveEntryReferrer obj) {
			synchronized (referrers) {
				boolean removed = referrers.remove(obj);
				logger.debug(liveEntry.id + " referrer count : "
						+ referrers.size());
				if (referrers.isEmpty()) {

					synchronized (entries) {
						logger.info("Remove object [" + liveEntry.id
								+ "] from entry cache");
						entries.remove(liveEntry.id);
					}
					unregister();
				}
				return removed;
			}
		}

		public LiveEntryCache(KalturaLiveEntry liveEntry, KalturaMediaServerIndex serverIndex, String applicationName) {
			this(liveEntry);
			register(serverIndex, applicationName);
		}

		public LiveEntryCache(KalturaLiveEntry liveEntry) {
			this.liveEntry = liveEntry;

			loadAssetParams();
		}

		private void loadAssetParams() {
			if(liveEntry.conversionProfileId <= 0)
				return;

			KalturaConversionProfileAssetParamsFilter assetParamsFilter = new KalturaConversionProfileAssetParamsFilter();
			assetParamsFilter.conversionProfileIdEqual = liveEntry.conversionProfileId;

			KalturaLiveAssetFilter asstesFilter = new KalturaLiveAssetFilter();
			asstesFilter.entryIdEqual = liveEntry.id;
			
			KalturaClient impersonateClient = impersonate(liveEntry.partnerId);
			impersonateClient.startMultiRequest();
			try {
				impersonateClient.getConversionProfileAssetParamsService().list(assetParamsFilter);
				impersonateClient.getFlavorAssetService().list(asstesFilter);
				KalturaMultiResponse responses = impersonateClient.doMultiRequest();
				
				
				Object conversionProfileAssetParamsList = responses.get(0);
				Object flavorAssetsList = responses.get(1);
				
				if(conversionProfileAssetParamsList instanceof KalturaConversionProfileAssetParamsListResponse)
					conversionProfileAssetParams = ((KalturaConversionProfileAssetParamsListResponse) conversionProfileAssetParamsList).objects;

				if(flavorAssetsList instanceof KalturaFlavorAssetListResponse){
					for(KalturaFlavorAsset liveAsset : ((KalturaFlavorAssetListResponse) flavorAssetsList).objects){
						if(liveAsset instanceof KalturaLiveAsset){
							liveAssets.put(liveAsset.flavorParamsId, (KalturaLiveAsset) liveAsset);
							
							if(!liveAssetParams.containsKey(liveAsset.flavorParamsId)){
								KalturaFlavorParams liveParams = impersonateClient.getFlavorParamsService().get(liveAsset.flavorParamsId);
								if(liveParams instanceof KalturaLiveParams)
									liveAssetParams.put(liveAsset.flavorParamsId, (KalturaLiveParams) liveParams);
							}
						}
					}
				}
				
			} catch (KalturaApiException e) {
				logger.error("Failed to load asset params for live entry [" + liveEntry.id + "]:" + e.getMessage());
			}
			impersonateClient = null;
		}

		public synchronized void register(KalturaMediaServerIndex serverIndex, String appName) {
			logger.debug("Register [" + liveEntry.id + "]");
			if(registered)
				return;

			index = serverIndex;

			TimerTask setMediaServerTask = new TimerTask() {

				@Override
				public void run() {
					logger.debug("Initial timer task running [" + liveEntry.id + "]");
					setEntryMediaServer(liveEntry, index);
				}
			};
			
			timer = new Timer("register-" + liveEntry.id, true);
			timer.schedule(setMediaServerTask, isLiveRegistrationMinBufferTime);
			logger.debug("Scheduled initial timer [" + liveEntry.id + "]");

			registerTime = new Date();
			registerTime.setTime(registerTime.getTime() + isLiveRegistrationMinBufferTime);

			registered = true;
		}

		public synchronized void unregister() {
			logger.debug("LiveEntryCache::unregister");
			KalturaMediaServerIndex tmpIndex = index;
			index = null;
			registerTime = null;

			if(timer != null){
				timer.cancel();
				timer.purge();
				timer = null;
			}
			registered = false;
			
			unsetEntryMediaServer(liveEntry, tmpIndex);
		}

		public boolean isRegistered() {
			if (!registered)
				return false;

			return registerTime.before(new Date());
		}

		public void setLiveEntry(KalturaLiveEntry liveEntry) {
			this.liveEntry = liveEntry;
		}

		public KalturaLiveEntry getLiveEntry() {
			return liveEntry;
		}

		public ArrayList<KalturaConversionProfileAssetParams> getConversionProfileAssetParams() {
			return conversionProfileAssetParams;
		}

		public KalturaLiveAsset getLiveAsset(int assetParamsId) {
			if(liveAssets.containsKey(assetParamsId))
				return liveAssets.get(assetParamsId);
			
			logger.error("Asset params id [" + assetParamsId + "] not found");
			return null;
		}

		public KalturaLiveAsset getLiveAsset(String streamSuffix) {
			KalturaLiveParams assetParams;
			for(KalturaLiveAsset liveAsset : liveAssets.values()){
				assetParams = getLiveAssetParams(liveAsset.flavorParamsId);
				if(assetParams != null && assetParams.streamSuffix != null && assetParams.streamSuffix.equals(streamSuffix)){
					return liveAsset;
				}
			}
			
			logger.error("Asset with stream suffix [" + streamSuffix + "] not found");
			return null;
		}
		
		public KalturaLiveAsset getLiveAssetById(String assetId) {
			for(KalturaLiveAsset liveAsset : liveAssets.values()){
				if(liveAsset.id == assetId){
					return liveAsset;
				}
			}
			
			logger.error("Asset with id [" + assetId + "] not found");
			return null;
		}
		
		public Integer[] getLiveAssetParamsIds() {
			return liveAssets.keySet().toArray(new Integer[0]);
		}

		public KalturaMediaServerIndex getIndex() {
			return index;
		}
	}
	
	abstract public KalturaServiceBase getLiveServiceInstance (KalturaClient impersonateClient);
	
	abstract protected void disconnectStream (String entryId); 

	public KalturaLiveEntry get(String entryId) {

		synchronized (entries) {
			if (entries.containsKey(entryId)) {
				LiveEntryCache liveEntryCache = entries.get(entryId);
				return liveEntryCache.getLiveEntry();
			}
		}

		return null;
	}
	
	public KalturaMediaServerIndex getMediaServerIndexForEntry (String entryId) {
		synchronized (entries) {
			if (entries.containsKey(entryId)) {
				LiveEntryCache liveEntryCache = entries.get(entryId);
				return liveEntryCache.getIndex();
			}
		}

		return null;
	}

	public KalturaLiveParams getLiveAssetParams(int assetParamsId){

		synchronized (liveAssetParams) {
			if (!liveAssetParams.containsKey(assetParamsId)) {
				logger.error("Asset params id [" + assetParamsId + "] not found");
				return null;
			}
			
			return liveAssetParams.get(assetParamsId);
		}
	}
	
	public KalturaLiveAsset getLiveAsset(String entryId, int assetParamsId) {

		synchronized (entries) {
			if (!entries.containsKey(entryId)) {
				logger.error("Entry id [" + entryId + "] not found");
				return null;
			}
			
			LiveEntryCache liveEntryCache = entries.get(entryId);
			return liveEntryCache.getLiveAsset(assetParamsId);
		}
	}
	
	public KalturaLiveAsset getLiveAsset(String entryId, String streamSuffix) {

		synchronized (entries) {
			if (!entries.containsKey(entryId)) {
				logger.error("Entry id [" + entryId + "] not found");
				return null;
			}
			
			LiveEntryCache liveEntryCache = entries.get(entryId);
			return liveEntryCache.getLiveAsset(streamSuffix);
		}
	}
	
	public KalturaLiveAsset getLiveAssetById(String entryId, String assetId) {

		synchronized (entries) {
			if (!entries.containsKey(entryId)) {
				logger.error("Entry id [" + entryId + "] not found");
				return null;
			}
			
			LiveEntryCache liveEntryCache = entries.get(entryId);
			return liveEntryCache.getLiveAssetById(assetId);
		}
	}
	
	public Integer[] getLiveAssetParamsIds(String entryId) {

		synchronized (entries) {
			if (!entries.containsKey(entryId)) {
				logger.error("Entry id [" + entryId + "] not found");
				return null;
			}
			
			LiveEntryCache liveEntryCache = entries.get(entryId);
			return liveEntryCache.getLiveAssetParamsIds();
		}
	}
	
	public KalturaConversionProfileAssetParams getConversionProfileAssetParams(String entryId, int assetParamsId) {

		synchronized (entries) {
			if (!entries.containsKey(entryId)) {
				logger.error("Entry id [" + entryId + "] not found");
				return null;
			}
			
			LiveEntryCache liveEntryCache = entries.get(entryId);
			ArrayList<KalturaConversionProfileAssetParams> conversionProfileAssetParamsList = liveEntryCache.getConversionProfileAssetParams();
			for(KalturaConversionProfileAssetParams conversionProfileAssetParams : conversionProfileAssetParamsList){
				if(conversionProfileAssetParams.assetParamsId == assetParamsId)
					return conversionProfileAssetParams;
			}
		}

		logger.error("Asset id [" + assetParamsId + "] in entry [" + entryId + "] not found");
		return null;
	}
	
	public Integer getDvrWindow(KalturaLiveEntry liveEntry) {
		if (liveEntry.dvrStatus != KalturaDVRStatus.ENABLED)
			return null;

		int maxDvrWindow = Integer.parseInt((String) serverConfiguration.get(KalturaLiveManager.KALTURA_LIVE_STREAM_MAX_DVR_WINDOW));
		int dvrWindowSeconds = liveEntry.dvrWindow * 60;
		if (dvrWindowSeconds <= 0 || dvrWindowSeconds > maxDvrWindow)
			return maxDvrWindow;

		return dvrWindowSeconds;
	}


	@Override
	public void onEvent(IKalturaEvent event){
		KalturaStreamEvent streamEvent;

		if(event.getType() instanceof KalturaEventType){
			switch((KalturaEventType) event.getType())
			{
				case STREAM_PUBLISHED:
					streamEvent = (KalturaStreamEvent) event;
					onPublish(streamEvent.getEntryId(), streamEvent.getServerIndex(), streamEvent.getApplicationName());
					break;
		
				default:
					break;
			}
		}
	}
	
	protected void onPublish(String entryId, final KalturaMediaServerIndex serverIndex, String applicationName) {
		logger.debug("Entry [" + entryId + "]");

		LiveEntryCache liveEntryCache = null;

		synchronized (entries) {

			if (entries.containsKey(entryId)){
				liveEntryCache = entries.get(entryId);
				liveEntryCache.register(serverIndex, applicationName);
			}
			else{
				logger.error("entry [" + entryId + "] not found in entries array");
			}
		}

		onEntryPublished( liveEntryCache, serverIndex, applicationName );
	}

	protected void onEntryPublished(LiveEntryCache liveEntryCache, final KalturaMediaServerIndex serverIndex, String applicationName) {
		if ( liveEntryCache != null && liveEntryCache.getLiveEntry() != null ) {
			KalturaLiveEntry liveEntry = liveEntryCache.getLiveEntry();
			KalturaLiveEntry updatedLiveEntry;

			try {
				updatedLiveEntry = liveEntry.getClass().newInstance();
			} catch (Exception e) {
				logger.error("failed to instantiate [" + liveEntry.getClass().getName() + "]: " + e.getMessage());
				return;
			}

			// Set the time to be unix time stamp with milliseconds as the decimal fraction
			updatedLiveEntry.currentBroadcastStartTime = new Date().getTime() / 1000.0;

			KalturaClient impersonateClient = impersonate(liveEntry.partnerId);
			try {
				impersonateClient.getBaseEntryService().update(liveEntry.id, updatedLiveEntry);
			} catch (KalturaApiException e) {
				logger.error("failed to update entry [" + liveEntry.id + "]: " + e.getMessage());
			}
			impersonateClient = null;
		}
	}

	public boolean isEntryRegistered(String entryId) {
		boolean registered = false;
		synchronized (entries) {
			LiveEntryCache liveEntryCache = entries.get(entryId);
			if (liveEntryCache != null)
				registered = liveEntryCache.isRegistered();
		}

		return registered;
	}

	public void init() throws KalturaManagerException {

		super.init();
		loadLiveParams();
		
		isLiveRegistrationMinBufferTime = KalturaLiveManager.DEFAULT_IS_LIVE_REGISTRATION_MIN_BUFFER_TIME * 1000;
		if (serverConfiguration.containsKey(KalturaLiveManager.KALTURA_IS_LIVE_REGISTRATION_MIN_BUFFER_TIME))
			isLiveRegistrationMinBufferTime = Long.parseLong((String) serverConfiguration.get(KalturaLiveManager.KALTURA_IS_LIVE_REGISTRATION_MIN_BUFFER_TIME)) * 1000;

		long keepAliveInterval = Long.parseLong((String) serverConfiguration.get(KalturaLiveManager.KALTURA_LIVE_STREAM_KEEP_ALIVE_INTERVAL)) * 1000;

		if (keepAliveInterval > 0) {
			TimerTask setMediaServerTask = new TimerTask() {

				@Override
				public void run() {
					logger.debug("Running scheduled task");
					try {
						synchronized (entries) {
							for (String entryId : entries.keySet()) {
								LiveEntryCache liveEntryCache = entries.get(entryId);
								logger.debug("handling entry " + entryId);
								if (liveEntryCache.isRegistered()) {
									logger.debug("re-registering media server");
									entryStillAlive(liveEntryCache.getLiveEntry(), liveEntryCache.index);
								}
							}
						}
					}
					catch (Exception e){
						logger.error(e);
					}
				}
			};

			setMediaServerTimer = new Timer("setMediaServerTimer", true);
			setMediaServerTimer.schedule(setMediaServerTask, keepAliveInterval, keepAliveInterval);
			logger.debug("scheduled setMediaServerTask");
		}
		
		KalturaEventsManager.registerEventConsumer(this, KalturaEventType.STREAM_PUBLISHED);
	}

	private void loadLiveParams() {
		try {
			KalturaFlavorParamsListResponse flavorParamsList = client.getFlavorParamsService().list();
			for(KalturaFlavorParams flavorParams : flavorParamsList.objects){
				if(flavorParams instanceof KalturaLiveParams)
					liveAssetParams.put(flavorParams.id, (KalturaLiveParams) flavorParams);
			}
			logger.info("loaded live params [" + liveAssetParams.size() + "]");
		} catch (KalturaApiException e) {
			logger.error("failed to load live params: " + e.getMessage());
		}
	}

	public void stop() {
		setMediaServerTimer.cancel();
		setMediaServerTimer.purge();
	}
	
	public void appendRecording(String entryId, String assetId, KalturaMediaServerIndex index, String filePath, double duration, boolean isLastChunk) {

		logger.info("Entry [" + entryId + "] asset [" + assetId + "] index [" + index + "] filePath [" + filePath + "] duration [" + duration + "] isLastChunk [" + isLastChunk + "]");
		
		KalturaLiveEntry liveEntry = get(entryId);
		if(liveEntry == null){
			logger.info("Entry [" + entryId + "] not found");
			return;
		}
		
		if (serverConfiguration.containsKey(KalturaLiveManager.UPLOAD_XML_SAVE_PATH))
		{
			boolean result = saveUploadAsXml (entryId, assetId, index, filePath, duration, isLastChunk, liveEntry.partnerId);
			if (result) {
				liveEntry.msDuration += duration;
				LiveEntryCache liveEntryCache = entries.get(entryId);
				liveEntryCache.setLiveEntry(liveEntry);
				return;
			}
				
		}
		
		KalturaDataCenterContentResource resource = getContentResource(filePath, liveEntry);
		
		KalturaClient impersonateClient = impersonate(liveEntry.partnerId);
		KalturaServiceBase liveServiceInstance = getLiveServiceInstance(impersonateClient);
		
		try {
			
			Method method = liveServiceInstance.getClass().getMethod("appendRecording", String.class, String.class, KalturaMediaServerIndex.class, KalturaDataCenterContentResource.class, double.class, boolean.class);
			KalturaLiveEntry updatedEntry = (KalturaLiveEntry)method.invoke(liveServiceInstance, entryId, assetId, index, resource, duration, isLastChunk);
			
			if(updatedEntry != null){
				synchronized (entries) {
					LiveEntryCache liveEntryCache = entries.get(entryId);
					if(liveEntryCache != null){
						liveEntryCache.setLiveEntry(updatedEntry);
					}
					else{
						entries.put(entryId, new LiveEntryCache(updatedEntry));
					}
				}
			}
		}
		catch (Exception e) {
			if(e instanceof KalturaApiException && ((KalturaApiException) e).code == KalturaLiveManager.LIVE_STREAM_EXCEEDED_MAX_RECORDED_DURATION){
				logger.info("Entry [" + entryId + "] exceeded max recording duration: " + e.getMessage());
			}
			logger.error("Unexpected error occurred [" + entryId + "]", e);
		}
		
		impersonateClient = null;
		
	}
	
	protected KalturaDataCenterContentResource getContentResource (String filePath, KalturaLiveEntry liveEntry) {
		if (!this.serverConfiguration.containsKey(KALTURA_WOWZA_SERVER_WORK_MODE) || (this.serverConfiguration.get(KALTURA_WOWZA_SERVER_WORK_MODE).equals(KALTURA_WOWZA_SERVER_WORK_MODE_KALTURA))) {
			KalturaServerFileResource resource = new KalturaServerFileResource();
			resource.localFilePath = filePath;
			return resource;
		}
		else {
			KalturaClient impersonateClient = impersonate(liveEntry.partnerId);
			try {
				impersonateClient.startMultiRequest();
				impersonateClient.getUploadTokenService().add(new KalturaUploadToken());
				
				File fileData = new File(filePath);
				impersonateClient.getUploadTokenService().upload("{1:result:id}", new KalturaFile(fileData));
				KalturaMultiResponse responses = impersonateClient.doMultiRequest();
				
				KalturaUploadedFileTokenResource resource = new KalturaUploadedFileTokenResource();
				Object response = responses.get(1);
				if (response instanceof KalturaUploadToken)
					resource.token = ((KalturaUploadToken)response).id;
				else {
					if (response instanceof KalturaApiException) {
				}
					logger.error("Content resource creation error: " + ((KalturaApiException)response).getMessage());
					return null;
				}
					
				return resource;
				
			} catch (KalturaApiException e) {
				logger.error("Content resource creation error: " + e.getMessage());
			}
			impersonateClient = null;
		}
		
		return null;
	}

	protected void entryStillAlive(KalturaLiveEntry liveEntry, KalturaMediaServerIndex serverIndex) {
		setEntryMediaServer(liveEntry, serverIndex);
	}

	protected void setEntryMediaServer(KalturaLiveEntry liveEntry, KalturaMediaServerIndex serverIndex) {
		logger.debug("Register media server [" + hostname + "] entry [" + liveEntry.id + "] index [" + serverIndex.hashCode + "]");
		
		KalturaClient impersonateClient = impersonate(liveEntry.partnerId);
		KalturaServiceBase liveServiceInstance = getLiveServiceInstance(impersonateClient);
		try {
			Method method = liveServiceInstance.getClass().getMethod("registerMediaServer", String.class, String.class, KalturaMediaServerIndex.class);
			method.invoke(liveServiceInstance, liveEntry.id, hostname, serverIndex);
		} catch (Exception e) {
			if (e instanceof InvocationTargetException) {
				Throwable target = ((InvocationTargetException) e).getTargetException();
				logger.error("Unable to register media server [" + liveEntry.id + "]: " + target.getMessage());
				if (target instanceof KalturaApiException && ((KalturaApiException) target).code.equals("SERVICE_FORBIDDEN_CONTENT_BLOCKED")) {
					logger.info("About to disconnect stream " + liveEntry.id);
					this.disconnectStream(liveEntry.id);
				}
			}
		}
		impersonateClient = null;
	}

	protected void unsetEntryMediaServer(KalturaLiveEntry liveEntry, KalturaMediaServerIndex serverIndex) {
		logger.debug("Unregister media server [" + hostname + "] entry [" + liveEntry.id + "] index [" + serverIndex.hashCode + "]");
		
		KalturaClient impersonateClient = impersonate(liveEntry.partnerId);
		KalturaServiceBase liveServiceInstance = getLiveServiceInstance(impersonateClient);
		try {
			Method method = liveServiceInstance.getClass().getMethod("unregisterMediaServer", String.class, String.class, KalturaMediaServerIndex.class);
			method.invoke(liveServiceInstance, liveEntry.id, hostname, serverIndex);
		} catch (Exception e) {
			logger.error("Unable to unregister media server [" + liveEntry.id + "]: ", e);
		}
		impersonateClient = null;
	}
	
	public KalturaLiveEntry reloadEntry(String entryId, int partnerId) {
		KalturaClient impersonateClient = impersonate(partnerId);
		KalturaServiceBase liveServiceInstance = getLiveServiceInstance(impersonateClient);
		KalturaLiveEntry liveEntry;
		try {
			Method method = liveServiceInstance.getClass().getMethod("get", String.class);
			liveEntry = (KalturaLiveEntry)method.invoke(liveServiceInstance, entryId);
			impersonateClient = null;
		} catch (Exception e) {
			impersonateClient = null;
			logger.error("KalturaLiveManager::reloadEntry unable to get entry [" + entryId + "]: " + e.getMessage());
			return null;
		}

		synchronized (entries) {
			LiveEntryCache liveEntryCache = entries.get(entryId);
			liveEntryCache.setLiveEntry(liveEntry);
		}
		
		return liveEntry;
	}
	
	protected boolean saveUploadAsXml (String entryId, String assetId, KalturaMediaServerIndex index, String filePath, double duration, boolean isLastChunk, int partnerId)
	{
		try {
			DocumentBuilderFactory docFactory = DocumentBuilderFactory.newInstance();
			DocumentBuilder docBuilder = docFactory.newDocumentBuilder();
			Document doc = docBuilder.newDocument();
			Element rootElement = doc.createElement("upload");
			doc.appendChild(rootElement);
			
			// entryId element
			Element entryIdElem = doc.createElement("entryId");
			entryIdElem.appendChild(doc.createTextNode(entryId));
			rootElement.appendChild(entryIdElem);
			
			// assetId element
			Element assetIdElem = doc.createElement("assetId");
			assetIdElem.appendChild(doc.createTextNode(assetId));
			rootElement.appendChild(assetIdElem);
			
			// partnerId element
			Element partnerIdElem = doc.createElement("partnerId");
			partnerIdElem.appendChild(doc.createTextNode(Integer.toString(partnerId)));
			rootElement.appendChild(partnerIdElem);
			
			// index element
			Element indexElem = doc.createElement("index");
			indexElem.appendChild(doc.createTextNode(Integer.toString(index.hashCode)));
			rootElement.appendChild(indexElem);
			
			// duration element
			Element durationElem = doc.createElement("duration");
			durationElem.appendChild(doc.createTextNode(Double.toString(duration)));
			rootElement.appendChild(durationElem);
			
			// isLastChunk element
			Element isLastChunkElem = doc.createElement("isLastChunk");
			isLastChunkElem.appendChild(doc.createTextNode(Boolean.toString(isLastChunk)));
			rootElement.appendChild(isLastChunkElem);

			// filepath element
			Element filepathElem = doc.createElement("filepath");
			filepathElem.appendChild(doc.createTextNode(filePath));
			rootElement.appendChild(filepathElem);
			
			// workmode element
			String workmode = serverConfiguration.containsKey(KalturaLiveManager.KALTURA_WOWZA_SERVER_WORK_MODE) ? (String)serverConfiguration.get(KalturaLiveManager.KALTURA_WOWZA_SERVER_WORK_MODE) : KalturaLiveManager.KALTURA_WOWZA_SERVER_WORK_MODE_KALTURA;
			Element workmodeElem = doc.createElement("workMode");
			workmodeElem.appendChild(doc.createTextNode(workmode));
			rootElement.appendChild(workmodeElem);
			
			// write the content into xml file
			TransformerFactory transformerFactory = TransformerFactory.newInstance();
			Transformer transformer = transformerFactory.newTransformer();
			DOMSource source = new DOMSource(doc);
			
			String xmlFilePath = buildXmlFilePath(entryId, assetId);
			StreamResult result = new StreamResult(new File(xmlFilePath));
	 
			// Output to console for testing
			// StreamResult result = new StreamResult(System.out);
	 
			transformer.transform(source, result);
	 
			logger.info("Upload XML saved at: " + xmlFilePath);
			return true;
		}
		catch (Exception e) {
			logger.error("Error occurred creating upload XML: " + e.getMessage());
			return false;
		}
	}

	private String buildXmlFilePath(String entryId, String assetId) {
		StringBuilder sb = new StringBuilder();
		sb.append(serverConfiguration.get(KalturaLiveManager.UPLOAD_XML_SAVE_PATH));
		sb.append("/");
		sb.append(entryId);
		sb.append("_"); 
		sb.append(assetId);
		sb.append("_");
		sb.append(System.currentTimeMillis());
		sb.append(".xml");
		return sb.toString();
	}
	
	
	public void cancelReplace (String entryId) {
		logger.info("Cancel replacement is required");
		KalturaLiveEntry liveEntry = get(entryId);
		
		KalturaClient impersonateClient = impersonate(liveEntry.partnerId);
		try {
			if (liveEntry.recordedEntryId != null && liveEntry.recordedEntryId.length() > 0) {
				impersonateClient.getMediaService().cancelReplace(liveEntry.recordedEntryId);
			}
		}
		catch (Exception e)
		{
			logger.error("Error occured: " + e.getMessage());
		}
	}
	
	public void addReferrer(String entryId, ILiveEntryReferrer obj) {
		logger.info("Add [" + obj.getClass().getName() + "] as referrer of ["
				+ entryId + "]");
		synchronized (entries) {
			LiveEntryCache liveEntryCache = entries.get(entryId);
			if (liveEntryCache == null) {
				logger.error("Couldn't add referrer becuase entry doesn't exist in cache");
			} else {
				liveEntryCache.addReferrer(obj);
				logger.info("Referrer added " + obj.getClass().getName());
			}
		}
	}

	public void removeReferrer(String entryId, ILiveEntryReferrer obj) {
		logger.info("Remove [" + obj.getClass().getName()
				+ "] from being referrer of [" + entryId + "]");
		boolean removed = false;
		synchronized (entries) {
			LiveEntryCache liveEntryCache = entries.get(entryId);
			if (liveEntryCache != null) {
				removed = liveEntryCache.removeReferrer(obj);
			}
		}

		if (removed) {
			logger.info("Referrer removed successfully");
		} else {
			logger.error("Failed to remove referrer");
		}
	}
}
