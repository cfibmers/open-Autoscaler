

package org.cloudfoundry.autoscaler.application;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.log4j.Logger;
import org.cloudfoundry.autoscaler.Constants;
import org.cloudfoundry.autoscaler.ErrorCode;
import org.cloudfoundry.autoscaler.ScalingStateMonitor;
import org.cloudfoundry.autoscaler.ScalingStateMonitorTask;
import org.cloudfoundry.autoscaler.TriggerSubscriber;
import org.cloudfoundry.autoscaler.cloudservice.couchdb.data.document.Application;
import org.cloudfoundry.autoscaler.cloudservice.couchdb.data.document.AutoScalerPolicy;
import org.cloudfoundry.autoscaler.cloudservice.couchdb.data.document.ScheduledPolicy;
import org.cloudfoundry.autoscaler.cloudservice.couchdb.data.document.ScheduledPolicy.ScheduledType;
import org.cloudfoundry.autoscaler.cloudservice.manager.util.CloudFoundryErrorCode;
import org.cloudfoundry.autoscaler.event.ScalingStateManager;
import org.cloudfoundry.autoscaler.event.TriggerEventHandler;
import org.cloudfoundry.autoscaler.exceptions.AppNotFoundException;
import org.cloudfoundry.autoscaler.exceptions.CloudException;
import org.cloudfoundry.autoscaler.exceptions.DataStoreException;
import org.cloudfoundry.autoscaler.exceptions.MetricNotSupportedException;
import org.cloudfoundry.autoscaler.exceptions.MonitorServiceException;
import org.cloudfoundry.autoscaler.exceptions.NoAttachedPolicyException;
import org.cloudfoundry.autoscaler.exceptions.PolicyNotFoundException;
import org.cloudfoundry.autoscaler.exceptions.TriggerNotSubscribedException;
import org.cloudfoundry.autoscaler.metric.util.CloudFoundryManager;
import org.cloudfoundry.autoscaler.policy.PolicyManager;
import org.cloudfoundry.autoscaler.policy.PolicyManagerImpl;
import org.cloudfoundry.autoscaler.statestore.AutoScalingDataStore;
import org.cloudfoundry.autoscaler.statestore.AutoScalingDataStoreFactory;
import org.cloudfoundry.autoscaler.util.ScheduledServiceUtil;
/**
 * Implements the interface ApplicationManager
 * 
 *
 */
public class ApplicationManagerImpl implements ApplicationManager {
	private static final String CLASS_NAME = ApplicationManagerImpl.class.getName();
	private static final Logger logger     = Logger.getLogger(CLASS_NAME); 
	private static final ApplicationManagerImpl instance = new ApplicationManagerImpl();
	
	private ConcurrentHashMap<String, Application> applicationCache = new ConcurrentHashMap<String, Application>();
	private ApplicationManagerImpl(){
		
	}
	public static ApplicationManagerImpl getInstance(){
		return instance;
	}
	@Override
	public void addApplication(NewAppRequestEntity newAppData)
			throws PolicyNotFoundException, MetricNotSupportedException,
			DataStoreException, MonitorServiceException, CloudException{
		logger.info("Add application");
		String appId = newAppData.getAppId();
		String orgId = newAppData.getOrgId();
		String spaceId = newAppData.getSpaceId();

		if (orgId == null || spaceId == null){
			logger.info("Call CF Rest API to get org and space of application " + appId);
			//Gets org space
			Map<String, String> orgSpace;
			try {
				orgSpace = CloudFoundryManager.getInstance().getOrgSpaceByAppId(appId);
				orgId = orgSpace.get("ORG_GUID");
				spaceId = orgSpace.get("SPACE_GUID");			
			} catch (Exception e) {
				logger.error ("Fail to add application since no valid org/space info for app " + appId);
				return;
			}
		}

		Application app = new Application(appId, newAppData.getServiceId(),
				newAppData.getBindingId(), orgId, spaceId);
		app.setState(Constants.APPLICATION_STATE_ENABLED);
		app.setBindTime(System.currentTimeMillis());
		AutoScalingDataStore dataStore = AutoScalingDataStoreFactory
				.getAutoScalingDataStore();
		Application oldApp = dataStore.getApplication(appId);
		if (oldApp != null && oldApp.getPolicyId() != null){
			app.setPolicyId(oldApp.getPolicyId());
			app.setPolicyState(oldApp.getPolicyState());
		}
		String policyId = app.getPolicyId();
		if (policyId != null && isPolicyEnabled(app)) {
			// get policy
			PolicyManager policyManager = PolicyManagerImpl.getInstance();
			AutoScalerPolicy policy = null;
			policy = policyManager.getPolicyById(policyId);
			try {
				handleInstancesByPolicy(appId, policy); // Check the maximum instances and minimum
														// instances
			} catch (AppNotFoundException e) {
				logger.warn("The application " + appId
						+ " doesn't finish staging yet. ", e);
			} catch (Exception e) {
				logger.warn(
						"Error happens when handle instance number by policy. ",
						e);
			}
			TriggerSubscriber tr = new TriggerSubscriber(appId, policy);
			// subscribe trigger
			tr.subscribeTriggers();
		}
		// Store application
		dataStore.saveApplication(app);
		applicationCache.put(appId, app);
		
	}

	@Override
	public void removeApplicationByBindingId(String bindingId) throws DataStoreException, PolicyNotFoundException, MonitorServiceException, NoAttachedPolicyException {
		AutoScalingDataStore dataStore = AutoScalingDataStoreFactory
				.getAutoScalingDataStore();
		Application app = dataStore.getApplicationByBindingId(bindingId);

		if (app!=null){
		try {

				TriggerSubscriber tr = new TriggerSubscriber(app.getAppId(), null);
				tr.unSubscribeTriggers();
			
			} catch (TriggerNotSubscribedException e) {
				logger.warn("Trigger not found on monitor service.");
			} 
			app.setState(Constants.APPLICATION_STATE_UNBOND);
			dataStore.saveApplication(app);
			applicationCache.put(app.getAppId(), app);
			
		}
		else {
			logger.error ("ERRROR: Can't find expected App with binding id " + bindingId) ;
		}
	}

	@Override
	public Application getApplicationByBindingId(String bindingId) throws DataStoreException {
		AutoScalingDataStore dataStore = AutoScalingDataStoreFactory
				.getAutoScalingDataStore();
		return dataStore.getApplicationByBindingId(bindingId);
	}

	@Override
	public void updatePolicyOfApplication(String appId, String policyState, AutoScalerPolicy policy) throws MonitorServiceException, MetricNotSupportedException {
		try {
			if (AutoScalerPolicy.STATE_ENABLED.equals(policyState)){
				logger.info("The policy is enabled for application " + appId);
				handleInstancesByPolicy(appId, policy); //Check the maximum instances and minimum instances
			}
			else{
				logger.info("The policy is disabled for application " + appId);
			}
			
		} catch (AppNotFoundException e) {
			logger.warn( "The application " + appId + " doesn't finish staging yet. ", e);
		} catch (DataStoreException e) {
			logger.warn( "The data store can not be accessed. ", e);
		} catch (Exception e) {
			logger.warn( "Error happens when handle handle instance number by policy. ", e);
		}
		TriggerSubscriber tr = new TriggerSubscriber(appId, policy);
		try {
			tr.unSubscribeTriggers();
		} catch (TriggerNotSubscribedException e) {
			//Ignore
		}
		if (AutoScalerPolicy.STATE_ENABLED.equals(policyState))
			tr.subscribeTriggers();
		
	}

	@Override
	public List<Application> getApplications(String serviceId) throws DataStoreException {
		AutoScalingDataStore dataStore = AutoScalingDataStoreFactory
				.getAutoScalingDataStore();
		return dataStore.getApplications(serviceId);
	}

	public void handleInstancesByPolicy(String appId, AutoScalerPolicy policy) throws Exception{
		int minCount = policy.getCurrentInstanceMinCount();
		int maxCount = policy.getCurrentInstanceMaxCount();
		String scheduleType = policy.getCurrentScheduleType();
		String timeZone = policy.getTimezone();
		String startTimeStr = policy.getCurrentScheduleStartTime();
		Long startTime = null;
		Integer dayOfWeek = null;
		if(null != startTimeStr && !"".equals(startTimeStr)&& null != scheduleType && !"".equals(scheduleType))
		{
			if(ScheduledType.RECURRING.name().equals(scheduleType))
			{
				try {
					startTime = new SimpleDateFormat(ScheduledPolicy.recurringDateFormat).parse(startTimeStr).getTime();
				} catch (ParseException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				dayOfWeek = ScheduledServiceUtil.dayOfWeek(new Date());
			}
			else if(ScheduledType.SPECIALDATE.name().equals(scheduleType))
			{
				try {
					startTime = new SimpleDateFormat(ScheduledPolicy.specialDateDateFormat).parse(startTimeStr).getTime();
				} catch (ParseException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
			
			TimeZone curTimeZone = TimeZone.getDefault();
			TimeZone policyTimeZone = TimeZone.getDefault();
			String zoneName = "";
			if(null != timeZone && !"".equals(timeZone))
			{
				timeZone = timeZone.trim().replaceAll("\\s+", "");
				int index1 = timeZone.indexOf("(");
				int index2 = timeZone.indexOf(")");
				if(index2 >= 0)
				{
					zoneName = timeZone.substring(index2+1, timeZone.length()).trim();
				}
				else
				{
					if(index2 > index1)
					{
						zoneName = timeZone.substring(index1+1, index2);
						
					}
				}
				policyTimeZone = TimeZone.getTimeZone(zoneName);
			}
			startTime = startTime - policyTimeZone.getRawOffset() + curTimeZone.getRawOffset();
		}
		CloudApplicationManager manager = CloudApplicationManager.getInstance();
		ScalingStateManager stateManager = ScalingStateManager.getInstance();
		int instances =  manager.getInstances(appId);
		int newCount = instances;
		boolean shouldAdjust = false;
		if (instances < minCount) {
			logger.info("The number of application instances is less than minimum instances limitation. Start scaling out.");
			newCount = minCount;
			shouldAdjust = true;
		} else if (instances > maxCount) {
			logger.info("The number of application instances is greater than minimum instances limitation. Start scaling in.");
			newCount = maxCount;
			shouldAdjust = true;
		}
		
		if (shouldAdjust) {
				String actionUUID = UUID.randomUUID().toString();
				if (stateManager.setScalingStateRealizing(appId, null, instances,
						newCount, null,
						TriggerEventHandler.TRIGGER_TYPE_POLICY_CHANGED, actionUUID, scheduleType,timeZone, startTime, dayOfWeek)) {
					try {
						//start scaling until update appState successfully
						manager.scaleApplication(appId, newCount);
						ScalingStateMonitorTask task = new ScalingStateMonitorTask(appId, newCount, actionUUID);
						ScalingStateMonitor.getInstance().monitor(task);
					} catch (CloudException e2) {
						String errorCode = e2.getErrorCode();
						if (CloudFoundryErrorCode.MemoryQuotaExceeded.equals(errorCode)){
							logger.error("Failed to scale application " + appId + ". You have exceeded your organization's memory limit.");
						}else{
							errorCode = ErrorCode.CloudFoundryInternalError;
							logger.error("Failed to scale application " + appId + "." + e2.getMessage());
						}
						int currentInstances = manager.getInstances(appId);
						stateManager.setScalingStateFailed(appId, null,
								currentInstances, currentInstances, null,
								TriggerEventHandler.TRIGGER_TYPE_POLICY_CHANGED, errorCode, actionUUID, scheduleType,timeZone, startTime, dayOfWeek);
							
					}catch (Exception e) {
						logger.error( "Failed to update application " + appId + " to scaling state." + e.getMessage(), e);
						int currentInstances = manager.getInstances(appId);
						stateManager.setScalingStateFailed(appId, null,
								currentInstances, currentInstances, null,
								TriggerEventHandler.TRIGGER_TYPE_POLICY_CHANGED, e.getMessage(), actionUUID, scheduleType,timeZone, startTime, dayOfWeek);
						
						return;
					}
				}
		
		}
	}
	@Override
	public Application getApplication(String appId) throws DataStoreException, CloudException {
		
		Application app = null;
		if (applicationCache.containsKey(appId)){ 
			app = applicationCache.get(appId);
		} else {
			app = AutoScalingDataStoreFactory.getAutoScalingDataStore().getApplication(appId);
			applicationCache.put(appId, app);
		}
		
		if (app!= null && app.getAppType() == null){
			try {
				String appType = CloudFoundryManager.getInstance().getAppType(appId);
				app.setAppType(appType);
				AutoScalingDataStoreFactory.getAutoScalingDataStore().saveApplication(app);
				applicationCache.put(appId, app);
			} catch (Exception e) {
				logger.error("Failed to get the app type for app " + appId, e);
			}
				
		}
		return app;
	}
	@Override
	public void attachPolicy(String appId, String policyId, String policyState) throws DataStoreException, MonitorServiceException, MetricNotSupportedException, PolicyNotFoundException {
		AutoScalingDataStore dataStore = AutoScalingDataStoreFactory
				.getAutoScalingDataStore();
		Application app = dataStore.getApplication(appId);
		app.setPolicyId(policyId);
		app.setPolicyState(policyState);
		dataStore.saveApplication(app);	
		applicationCache.put(appId,app);
		PolicyManager policyManager = PolicyManagerImpl.getInstance();
		AutoScalerPolicy policy = policyManager.getPolicyById(policyId);
		updatePolicyOfApplication(appId, policyState, policy);
	}
	@Override
	public void detachPolicy(String appId, String policyId, String policyState) throws DataStoreException, MonitorServiceException, MetricNotSupportedException, PolicyNotFoundException {
			AutoScalingDataStore dataStore = AutoScalingDataStoreFactory
					.getAutoScalingDataStore();
			Application app = dataStore.getApplication(appId);
			app.setPolicyId(null);
			app.setPolicyState(null);
			dataStore.saveApplication(app);	
			applicationCache.put(appId,app);
			PolicyManager policyManager = PolicyManagerImpl.getInstance();
			AutoScalerPolicy policy = policyManager.getPolicyById(policyId);
			TriggerSubscriber tr = new TriggerSubscriber(appId, policy);
			try {
				tr.unSubscribeTriggers();
			} catch (TriggerNotSubscribedException e) {
				//Ignore
			}
		}
	@Override
	public List<Application> getApplicationByPolicyId(String policyId)
			throws DataStoreException {
		AutoScalingDataStore dataStore = AutoScalingDataStoreFactory
				.getAutoScalingDataStore();
		return dataStore.getApplicationsByPolicyId(policyId);
	}


	
	/**
	 * Checks if app is enabled
	 * @param app
	 * @return
	 */
	private boolean isPolicyEnabled(Application app){
		if (AutoScalerPolicy.STATE_ENABLED.equals(app.getPolicyState())){
			return true;
		}else
			return false;
	}
	
	public void invalidateCache(){
		applicationCache.clear();
	}
}