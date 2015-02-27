package jenkins.plugins.testdroid;

import com.testdroid.api.*;
import com.testdroid.api.model.*;
import hudson.*;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Computer;
import hudson.tasks.BuildWrapper;
import hudson.tasks.BuildWrapperDescriptor;
import hudson.util.FormValidation;
import jenkins.model.Jenkins;
import net.sf.json.JSONArray;
import net.sf.json.JSONException;
import net.sf.json.JSONObject;
import net.sf.json.JSONSerializer;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpHost;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.export.ExportedBean;

import javax.servlet.ServletException;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

@ExportedBean
public class DeviceSessionWrapper extends BuildWrapper {

    private static final transient Logger LOGGER = Logger.getLogger(DeviceSessionWrapper.class.getName());

    private static final long serialVersionUID = 1L;

    //device label group which contains all the build version labels
    private final static String BUILD_IDENTIFIER_LABEL_GROUP = "Build Identifier";

    //default flash project name in testdroid TODO: add it as parameter
    private final static String FLASH_PROJECT_NAME = "flash-fxos";

    //parameter for location of build to flash TODO: add it as parameter
    private final static String BUILD_URL_PARAM = "FLAME_ZIP_URL";

    //parameter for total memory to allocate
    private final static String MEM_TOTAL_PARAM = "MEM_TOTAL";

    //maximum waiting time for flash project to finish
    private final static int FLASH_TIMEOUT = 10*60*1000;  //10mins

    private final static int FLASH_RETRIES = 3;

    private final static int WAIT_FOR_PROXY_TIMEOUT = 5*60*1000;  //5mins

    //Wait until device instance has been dedicated for device session
    private final static int WAIT_FOR_DEVICE_SESSION = 1*60*1000;  //1min

    private final static int POLL_INTERVAL = 10*1000;

    private final static String DEVICE_DATA_JSON_FILENAME = "device.json";

    //location of device image
    private String buildURL;
    //total memory to allocate
    private String memTotal;
    //device filters
    private ArrayList<DeviceFilter> deviceFilters = new ArrayList<DeviceFilter>();

    @DataBoundConstructor
    @SuppressWarnings("hiding")
    public DeviceSessionWrapper(String buildURL, String memTotal, ArrayList<DeviceFilter> deviceFilters) {
        this.buildURL = buildURL;
        this.memTotal = memTotal;
        this.deviceFilters = deviceFilters;
    }

    private APIClient getAPIClient(TestdroidLogger logger) {
        DescriptorImpl descriptor = (DescriptorImpl) Jenkins.getInstance().getDescriptor(getClass());
        HttpHost proxy = null;
        ProxyConfiguration proxyConfiguration = Jenkins.getInstance().proxy;
        if (proxyConfiguration != null) {
            proxy = new HttpHost(proxyConfiguration.name, proxyConfiguration.port);
        }
        logger.info("Connecting to " + descriptor.endPointURL + " as " + descriptor.username + (proxy != null ? " using proxy " + proxy.toString() : ""));
        APIClient client = null;
        if (proxy != null) {
            //TODO: Support proxy authentication
            //TODO: Consider no_proxy hosts
            client = new DefaultAPIClient(descriptor.endPointURL, descriptor.username, descriptor.password, proxy, false);
        } else {
            client = new DefaultAPIClient(descriptor.endPointURL, descriptor.username, descriptor.password);
        }
        return client;
    }

    /**
     * Sets up build environment
     * <p/>
     * Create device session here and inject device session Id(+other params)
     */
    @Override
    @SuppressWarnings({"hiding", "unchecked"})
    public Environment setUp(final AbstractBuild build, final Launcher launcher, final BuildListener listener) throws IOException, InterruptedException {
        DescriptorImpl descriptor = (DescriptorImpl) Jenkins.getInstance().getDescriptor(getClass());
        TestdroidLogger logger = new TestdroidLogger(listener);
        APIClient client = getAPIClient(logger);
        APIUser user = null;

        //authorize
        try {
            user = client.me();
        } catch (APIException e) {
            logger.error("Connection failed! " + e.getMessage());
            throw new IOException(e);
        }

        final String host = new URL(descriptor.endPointURL).getHost();

        String finalBuildURL = applyMacro(build, listener, getBuildURL());
        String finalMemTotal = applyMacro(build, listener, getMemTotal());

        String buildIdentifier = String.format("%s_%s", finalMemTotal, finalBuildURL);

        APIDevice device;

        APIDeviceSession session = null;

        int retries = FLASH_RETRIES;
        do {

            try {
                device = getDevice(logger, client, getDeviceFilters(), buildIdentifier, finalBuildURL, finalMemTotal);
            } catch (APIException e) {
                logger.error("Failed to retrieve device by build id " + e.getMessage());
                throw new IOException(e);
            }

            Map<String, String> deviceSessionsParams = new HashMap<String, String>();
            deviceSessionsParams.put("deviceModelId", device.getId().toString());

            //in this phase we have found device with specific label, however it might not be available anymore
            //1) request device session
            try {
                session = client.post("/me/device-sessions", deviceSessionsParams, APIDeviceSession.class);
            } catch (APIException e) {
                //allow to continue if device lock can't be created otherwise throw IOException
                if (e.getStatus() != 400) {
                    logger.info("Failed to start device session " + e.getMessage());
                    throw new IOException(e);
                }
            }

            if(session != null && !waitUntilDeviceSessionIsRunning(session, WAIT_FOR_DEVICE_SESSION) ) {
                logger.info("Timeout when waiting for device session "+session.getId());
                releaseDeviceSession(logger, client, session);
                session = null;

            }

        } while (session == null && retries-- > 0);

        if(session == null) {
            logger.error("Failed to find device!");
            throw new IOException("Device session is null");
        }

        logger.info(String.format("Started session %d on device %d", session.getId(), device.getId()));
        LOGGER.log(Level.INFO, String.format("Started session %d on device %d", session.getId(), device.getId()));

        writeDeviceDataJSON(build, launcher, listener, client, device, DEVICE_DATA_JSON_FILENAME);

        JSONObject adb;
        JSONObject marionette;
        try {
            adb = getProxy("adb", client, session);
            logger.info("ADB port: " + adb.getString("port"));
            logger.info("ADB host: " + host);
            logger.info("Android serial: " + adb.getString("serialId"));
            marionette = getProxy("marionette", client, session);
            logger.info("Marionette port: " + marionette.getString("port"));
            logger.info("Marionette host: " + host);
        } catch (IOException ioe) {
            logger.info("Failed to fetch proxy entries " + ioe.getMessage());
            releaseDeviceSession(logger, client, session);
            throw ioe;
        } catch (InterruptedException ie) {
            logger.info("Failed to fetch proxy entries " + ie.getMessage());
            releaseDeviceSession(logger, client, session);
            throw ie;
        }

        return new TestdroidSessionEnvironment(client, session, adb, marionette) {

            @Override
            public void buildEnvVars(Map<String, String> env) {
                env.put("SESSION_ID", Long.toString(apiDeviceSession.getId()));
                env.put("ADB_PORT", adbJSONObject.getString("port"));
                env.put("ADB_HOST", host);
                env.put("DEVICE_DATA", DEVICE_DATA_JSON_FILENAME);
                env.put("ANDROID_SERIAL", adbJSONObject.getString("serialId"));
                env.put("MARIONETTE_PORT", marionetteJSONObject.getString("port"));
                env.put("MARIONETTE_HOST", host);
            }

            @Override
            @SuppressWarnings("unchecked")
            public boolean tearDown(AbstractBuild build, BuildListener listener)
                    throws IOException, InterruptedException {
                TestdroidLogger logger = new TestdroidLogger(listener);

                if(apiDeviceSession == null) {
                    LOGGER.log(Level.WARNING, "Session was not initialized, skipping session release");
                    return true;
                }
                try {
                    releaseDeviceSession(logger, getApiClient(), getApiDeviceSession());
                } catch (IOException e) {
                    //Recreate API client as tokens(auth or/and refresh tokens might be expired
                    final APIClient client = getAPIClient(logger);
                    releaseDeviceSession(logger, client, getApiDeviceSession());
                }
                return true;
            }
        };
    }

    /**
     * Wait until DeviceSession state is "running" or timeout occurs.
     * @param apiDeviceSession
     * @param timeout
     * @return true if device session is running
     * @throws InterruptedException
     * @throws IOException
     */
    private boolean waitUntilDeviceSessionIsRunning(APIDeviceSession apiDeviceSession, int timeout) throws InterruptedException, IOException {

        long waitUntil = System.currentTimeMillis() + timeout;
        while (apiDeviceSession.getState().equals(APIDeviceSession.State.WAITING) &&
                waitUntil > System.currentTimeMillis()) {
            Thread.sleep(5000);
            try {
                apiDeviceSession.refresh();
            } catch (APIException e) {
                //ignore
            }
        }

        if (apiDeviceSession.getState().equals(APIDeviceSession.State.RUNNING)) {
            return true;
        }

        return false;
    }

    /**
     * Write the device label data into the file in json format.
     *
     * @param build
     * @param launcher
     * @param listener
     * @param client
     * @param device
     * @param jsonFileName
     * @throws InterruptedException
     * @throws IOException
     */
    private void writeDeviceDataJSON(final AbstractBuild build, final Launcher launcher, BuildListener listener,
                                    APIClient client, APIDevice device, String jsonFileName)
            throws InterruptedException, IOException {
        TestdroidLogger logger = new TestdroidLogger(listener);
        URI workspaceURI = build.getWorkspace().toURI();
        FilePath deviceDataFile = new FilePath(launcher.getChannel(), workspaceURI.getPath() + "/" + jsonFileName);


        try {
            APIList<APIDeviceProperty> deviceProperties = client.get(String.format("/devices/%d/properties?limit=0", device.getId()), APIList.class);

            if (deviceProperties == null || deviceProperties.isEmpty()) {
                LOGGER.log(Level.INFO, "No device labels have been set for device with ID: " + device.getId());
                return;
            }

            JSONObject jsonObject = new JSONObject();
            for (APIDeviceProperty property : deviceProperties.getData()) {
                String groupName = property.getPropertyGroupName().toLowerCase().replace(" ", "_");
                String labelName = property.getDisplayName();
                if (jsonObject.containsKey(groupName)) {
                    JSONArray labels = new JSONArray();
                    try {
                        labels.addAll(jsonObject.getJSONArray(groupName));
                    } catch (JSONException e) {
                        labels.add(jsonObject.get(groupName));
                    }
                    labels.add(labelName);
                    jsonObject.put(groupName, labels);
                } else {
                    jsonObject.put(groupName, labelName);
                }
            }

            deviceDataFile.write(jsonObject.toString(2), "UTF-8");
            LOGGER.log(Level.INFO, "Device data: " + jsonObject.toString());

        } catch (APIException e) {
            logger.error("APIException when reading device label information for device " + device.getId());
            LOGGER.log(Level.WARNING, "APIException", e);
        }

    }

    private abstract class TestdroidSessionEnvironment extends Environment {
        protected final APIClient apiClient;
        protected final APIDeviceSession apiDeviceSession;
        protected final JSONObject adbJSONObject;
        protected final JSONObject marionetteJSONObject;

        public TestdroidSessionEnvironment(APIClient apiClient, APIDeviceSession apiDeviceSession, JSONObject adbJSONObject, JSONObject marionetteJSONObject) {
            this.apiClient = apiClient;
            this.apiDeviceSession = apiDeviceSession;
            this.adbJSONObject = adbJSONObject;
            this.marionetteJSONObject = marionetteJSONObject;
        }

        public APIClient getApiClient() {
            return apiClient;
        }

        public APIDeviceSession getApiDeviceSession() {
            return apiDeviceSession;
        }

        public JSONObject getAdbJSONObject() {
            return adbJSONObject;
        }

        public JSONObject getMarionetteJSONObject() {
            return marionetteJSONObject;
        }
    }

    /**
     * Search device by label from "Build Version" label group. If device is not available flash device with specific
     * build seach device again.

     * @param logger
     * @param client
     * @param filters
     * @param buildIdentifier
     * @param buildURL
     * @param memTotal
     * @throws APIException
     * @throws IOException
     * @throws InterruptedException
     */
    private APIDevice getDevice(TestdroidLogger logger, APIClient client, ArrayList<DeviceFilter> filters, String buildIdentifier, String buildURL, String memTotal) throws APIException, IOException, InterruptedException {
        APIDevice device;
        int maxRetries = FLASH_RETRIES;

        ArrayList<DeviceFilter> searchFilters = new ArrayList<DeviceFilter>();
        ArrayList<DeviceFilter> flashFilters = new ArrayList<DeviceFilter>();

        if(filters != null ) {
            searchFilters = (ArrayList<DeviceFilter>) filters.clone();
            flashFilters = (ArrayList<DeviceFilter>) filters.clone();
        }
        //look for device having "Build Identifier" label with value {buildIdentifier}
        searchFilters.add(new DeviceFilter(BUILD_IDENTIFIER_LABEL_GROUP, buildIdentifier));
        while( (device = searchDevice(client, searchFilters, false)) == null) {
            if(maxRetries-- < 0) {
                logger.info(String.format("Flashing device failed, tried %d times but no device found", FLASH_RETRIES));
                throw new IOException("Device flashing failed");
            }
            //if not matching device is not found run flash project
            flashDevice(logger, client, flashFilters, buildURL, memTotal);
        }
        return device;
    }
    private void releaseDeviceSession(TestdroidLogger logger, APIClient apiClient, APIDeviceSession apiDeviceSession) throws IOException {
        logger.info("Releasing device session");
        try {
            apiClient.post(String.format("/me/device-sessions/%d/release", apiDeviceSession.getId()), null, null);
        } catch (APIException e) {
            logger.error("Failed to release device session " + e.getMessage());
            throw new IOException(e);
        }
    }

    /**
     * Replace macro with environment variable if it exists
     * @param build
     * @param listener
     * @param macro
     * @return
     * @throws InterruptedException
     */
    public static String applyMacro(AbstractBuild build, BuildListener listener, String macro)
            throws InterruptedException{
        try {
            EnvVars envVars = new EnvVars(Computer.currentComputer().getEnvironment());
            envVars.putAll(build.getEnvironment(listener));
            envVars.putAll(build.getBuildVariables());
            return Util.replaceMacro(macro, envVars);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to apply macro " + macro, e);
        }
        return macro;
    }
    /**
     * Run "flash" project and wait until it has completed
     * @return
     */
    public boolean flashDevice(TestdroidLogger logger, APIClient client, ArrayList<DeviceFilter> filters, String buildURL, String memTotal) throws APIException, IOException, InterruptedException {
        String memoryThrottled = Integer.parseInt(memTotal) > 0 ? " and memory throttled at " + memTotal + "MB" : "";
        logger.info("Flashing device with " + buildURL + memoryThrottled);

        APIUser user = client.me();
        APIListResource<APIProject>  projectAPIListResource = user.getProjectsResource(new APIQueryBuilder().search(FLASH_PROJECT_NAME));
        APIList<APIProject> projectList = projectAPIListResource.getEntity();
        if(projectList == null || projectList.getTotal() <= 0) {
            LOGGER.log(Level.SEVERE, "Unable find project: " + FLASH_PROJECT_NAME);
            return false;
        }
        APIProject flashProject = projectList.get(0);

        //Create test run
        Map<String, String> testRunParams = new HashMap<String, String>();
        testRunParams.put("projectId", flashProject.getId().toString());

        APITestRun testRun = client.post("/runs", testRunParams, APITestRun.class);

        //remove old params
        APITestRunConfig config = flashProject.getTestRun(testRun.getId()).getConfig();
        APIListResource<APITestRunParameter>  params = testRun.getConfig().getParameters();
        for(int i = 0; i < params.getEntity().getTotal(); i++) {
            APITestRunParameter param = params.getEntity().get(i);
            config.deleteParameter(param.getId());
        }
        config.createParameter(BUILD_URL_PARAM, buildURL);
        config.createParameter(MEM_TOTAL_PARAM, memTotal);

        APIDevice device = searchDevice(client, filters, true);

        if(device == null) {
            throw new IOException("Unable find device!");
        }

        Map<String,String> usedDevicesId = new HashMap<String, String>();
        usedDevicesId.put("usedDeviceIds[]",device.getId().toString());

        //start test run
        client.post(String.format("/runs/%s/start", testRun.getId()), usedDevicesId, APITestRun.class);
        testRun = flashProject.getTestRun(testRun.getId());
        long waitUntil = System.currentTimeMillis() + FLASH_TIMEOUT;
        while(!testRun.getState().equals(APITestRun.State.FINISHED)) {

            try {

                Thread.sleep(10000);

                if (waitUntil <  System.currentTimeMillis()) {
                    //abort run if it's still in WAITING state
                    testRun.refresh();
                    if(testRun.getState().equals(APITestRun.State.WAITING)) {
                        testRun.abort();
                    }
                    LOGGER.log(Level.SEVERE, String.format("Flash project didn't finish in %d seconds", FLASH_TIMEOUT / 1000));
                    return false;
                }
                testRun.refresh();
            } catch (InterruptedException ie) {
                testRun.abort();
                throw ie;

            }
        }
        return true;
    }

    public APIDevice searchDevice(APIClient client, ArrayList<DeviceFilter> filters, boolean lockedDeviceAllowed) throws APIException {
        List<Long> labelIds = new ArrayList<Long>();

        for(DeviceFilter f:filters) {
            LOGGER.log(Level.INFO, String.format("Looking for label %s: %s", f.group, f.label));

            //get label group
            APIListResource<APILabelGroup> labelGroupsResource = client
                    .getLabelGroups(new APIQueryBuilder().search(f.group));
            APIList<APILabelGroup> labelGroupsList = labelGroupsResource.getEntity();
            if(labelGroupsList == null || labelGroupsList.getTotal() <= 0) {
                LOGGER.log(Level.WARNING, "Unable to find label group: " + f.group);
                return null;
            }
            APILabelGroup labelGroup = labelGroupsList.get(0);

            //get label
            APIListResource<APIDeviceProperty> devicePropertiesResource = labelGroup
                    .getDevicePropertiesResource(new APIQueryBuilder().search(f.label));
            APIList<APIDeviceProperty> devicePropertiesList = devicePropertiesResource.getEntity();
            if(devicePropertiesList == null || devicePropertiesList.getTotal() <= 0) {
                LOGGER.log(Level.WARNING, "Unable to find label: " + f.label);
                return null;
            }
            int index = 0;
            //search for exact match
            for(APIDeviceProperty deviceProperty : devicePropertiesList.getData()) {
                if(f.label.equals(deviceProperty.getDisplayName())) {
                    break;
                }
                index++;
            }
            if(devicePropertiesList.getData().size() <= index) {
                LOGGER.log(Level.WARNING, "Unable to find label: " + f.label);
                return null;
            }
            APIDeviceProperty deviceProperty = devicePropertiesList.get(index);
            labelIds.add(deviceProperty.getId());
        }

        APIListResource<APIDevice> deviceListResource = null;
        if(labelIds.size() == 0) {
            deviceListResource = client.getDevices(new APIDeviceQueryBuilder().limit(0));
            LOGGER.log(Level.INFO, String.format("Found %s device(s)", deviceListResource.getTotal()));
        } else {
            LOGGER.log(Level.INFO, String.format("Looking for devices with labels: %s", labelIds.toString()));
            deviceListResource = client.getDevices(new APIDeviceQueryBuilder().limit(0)
                    .filterWithLabelIds(labelIds.toArray(new Long[labelIds.size()])));
            LOGGER.log(Level.INFO, String.format("Found %s device(s)", deviceListResource.getTotal()));
        }
        if(deviceListResource == null || deviceListResource.getTotal() == 0) {
            return null;
        }
        List<APIDevice> devices = deviceListResource.getEntity().getData();
        //shuffle list of of devices to avoid picking up the same device always
        Collections.shuffle(devices);

        //get the first online device with specific label
        //if lockedDeviceAllowed is true then return any locked device if unlocked can't be found
        APIDevice lockedDevice = null;

        for (APIDevice d : devices) {
            if(d.isOnline() && !d.isLocked()) {
                LOGGER.log(Level.INFO, String.format("Found device %d", d.getId()));
                return d;
            } else if(d.isOnline() && d.isLocked()) {
                lockedDevice = d;
            }
        }
        if(lockedDeviceAllowed) {
            return lockedDevice;
        }
        LOGGER.log(Level.INFO, String.format("Unable to find any devices with label(s)"));
        return null;
    }

    public String getBuildURL() {
        return buildURL;
    }

    public String getMemTotal() {
        return memTotal != null ? memTotal : "0";
    }

    public ArrayList<DeviceFilter> getDeviceFilters() {
        return deviceFilters;
    }

    private JSONObject getProxy(String type, APIClient client, APIDeviceSession session) throws IOException, InterruptedException {
        int tmp = 0;
        try {
            String response;
            JSONArray proxyEntries;
            String queryTemplate = "{\"type\":\"%s\", \"sessionId\": %d}";
            String proxyURL = String.format("/proxy-plugin/proxies?where=%s", URLEncoder.encode(String.format(queryTemplate, type, session.getId()), "UTF-8"));
            while((response = IOUtils.toString(client.get(proxyURL))) != null) {

                LOGGER.log(Level.WARNING, "Testdroid " + type + " proxy response: " + response + " URL: " + proxyURL);

                proxyEntries = (JSONArray) JSONSerializer.toJSON(response);
                if (proxyEntries.isEmpty()) {
                    if(tmp < WAIT_FOR_PROXY_TIMEOUT) {
                        Thread.sleep(POLL_INTERVAL);
                        tmp += POLL_INTERVAL;
                        continue;
                    }
                    throw new IOException("Failed to get proxy resource");
                }
                return proxyEntries.getJSONObject(0);
            }

        } catch (APIException e) {
            throw new IOException(e);
        }
        return null;
    }

    @Extension(ordinal = -90)
    public static final class DescriptorImpl extends BuildWrapperDescriptor {

        private static final long serialVersionUID = 1L;

        String endPointURL;
        String username;
        String password;

        public DescriptorImpl() {
            super(DeviceSessionWrapper.class);
            load();
        }

        @Override
        public String getDisplayName() {
            return "Testdroid device session";
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject json) throws FormException {
            req.bindParameters(this);
            this.endPointURL = json.getString("endPointURL");
            this.username = json.getString("username");
            this.password = json.getString("password");
            save();
            return true;
        }

        @Override
        public boolean isApplicable(AbstractProject<?, ?> item) {
            return true;
        }

        public String getEndPointURL() {
            return endPointURL;
        }

        public String getUsername() {
            return username;
        }

        public String getPassword() {
            return password;
        }

        public FormValidation doCheckBuildURL(@QueryParameter String value) throws IOException, ServletException {
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.error("Build URL is mandatory");
            } else if (value.contains("$")) {
                // Unable to expand environment variables during validation
                return FormValidation.ok();
            }
            try {
                new URI(value);
                return FormValidation.ok();
            } catch (Exception e) {
                return FormValidation.error("Build URL must be a valid URI. " + e.getMessage());
            }
        }

        public FormValidation doCheckEndPointURL(@QueryParameter String value) throws IOException, ServletException {
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.error("API endpoint is mandatory");
            }
            try {
                new URI(value);
                return FormValidation.ok();
            } catch (Exception e) {
                return FormValidation.error("End Point URL must be a valid URI. " + e.getMessage());
            }
        }

        public FormValidation doCheckMemTotal(@QueryParameter String value) throws IOException, ServletException {
            if (value.contains("$")) {
                // Unable to expand environment variables during validation
                return FormValidation.ok();
            }
            try {
                Integer memTotal = Integer.parseInt(value);
                if (memTotal >= 0) {
                    return FormValidation.ok();
                } else {
                    return FormValidation.error("Memory allocation must be 0 or greater");
                }
            } catch (NumberFormatException e) {
                return FormValidation.error("Memory allocation must be a number");
            }
        }

        public FormValidation doCheckPassword(@QueryParameter String value) throws IOException, ServletException {
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.error("Password is mandatory");
            } else {
                return FormValidation.ok();
            }
        }

        public FormValidation doCheckUsername(@QueryParameter String value) throws IOException, ServletException {
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.error("Username is mandatory");
            } else {
                return FormValidation.ok();
            }
        }
    }
}
