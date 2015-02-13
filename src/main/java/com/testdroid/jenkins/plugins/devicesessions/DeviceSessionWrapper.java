package com.testdroid.jenkins.plugins.devicesessions;

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
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;
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

    private final static int POLL_INTERVAL = 10*1000;

    private final static String DEVICE_DATA_JSON_FILENAME = "device.json";

    private DescriptorImpl descriptor;
    //testdroid API endpoint
    private String cloudURL;
    //location of device image
    private String buildURL;
    //total memory to allocate
    private String memTotal;
    //testdroid username
    private String username;
    //testdroid password
    private String password;
    //name or device model id
    private String deviceName;

    @DataBoundConstructor
    @SuppressWarnings("hiding")
    public DeviceSessionWrapper(String cloudURL, String username, String password, String deviceName, String buildURL, String memTotal) {
        this.cloudURL = cloudURL;
        this.username = username;
        this.password = password;
        this.buildURL = buildURL;
        this.memTotal = memTotal;
        this.deviceName = deviceName;
    }

    private APIClient getAPIClient(String cloudURL, String username, String password, ProxyConfiguration proxyConfiguration) {
        APIClient client = null;
        if (proxyConfiguration != null) {
            HttpHost proxy = new HttpHost(proxyConfiguration.name, proxyConfiguration.port);
            //TODO: Support proxy authentication
            //TODO: Consider no_proxy hosts

            client = new DefaultAPIClient(cloudURL, username, password, proxy, false);
        } else {
            client = new DefaultAPIClient(cloudURL, username, password);
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

        String cloudURL = applyMacro(build, listener, getCloudURL());


        ProxyConfiguration p = Jenkins.getInstance().proxy;
        listener.getLogger().println("Connecting to " + cloudURL + " as " + getUsername() + p != null ? " using proxy " + p.toString():"");

        APIClient client = getAPIClient(getCloudURL(),getUsername(), getPassword(), p);

        APIUser user = null;

        //authorize
        try {
            user = client.me();
        } catch (APIException e) {
            listener.getLogger().println("Connection failed! " + e.getMessage());
            throw new IOException(e);
        }

        final String cloudHost = new URL(cloudURL).getHost();
        String finalBuildURL = applyMacro(build, listener, getBuildURL());
        String buildIdentifier = String.format("%s_%s", getMemTotal(), finalBuildURL);


        //Look for device having "Build Identifier" label group with label {buildIdentifier}
        //if not matching device is not found run "reflash" project and look again.
        APIDevice device = null;
        try {
            device = getDeviceByLabel(client, buildIdentifier, finalBuildURL, getMemTotal(), listener);
        } catch (APIException e) {
            listener.getLogger().println("Failed to retrieve device by build id " + e.getMessage());
            throw new IOException(e);
        }

        listener.getLogger().println("Requesting device session for " + device.getDisplayName());

        Map<String, String> deviceSessionsParams = new HashMap<String, String>();
        deviceSessionsParams.put("deviceModelId", device.getId().toString());
        APIDeviceSession session = null;

        int retries = FLASH_RETRIES;
        do {
            //in this phase we have found device with specific label, however it might not be available anymore
            //1) request device session
            try {
                session = client.post("/me/device-sessions", deviceSessionsParams, APIDeviceSession.class);
            } catch (APIException e) {
                //allow to continue if device lock can't be created otherwise throw IOException
                if (e.getStatus() != 400) {
                    listener.getLogger().println("Failed to start device session " + e.getMessage());
                    throw new IOException(e);
                }
            }
            //If session can't be created, run flash project again
            if(session == null) {
                listener.getLogger().println("Device was not available");
                try {
                    runProject(listener, client, finalBuildURL, getMemTotal());
                } catch (APIException e) {
                    listener.getLogger().println("Failed to run project" + e.getMessage());
                    throw new IOException(e);
                }
            }
        } while (session == null && retries-- > 0);

        if(session == null) {
            listener.getLogger().println("Failed to find device with label: " + buildIdentifier);
            throw new IOException("Device session is null");
        }

        writeDeviceDataJSON(build, launcher, listener, client, device, DEVICE_DATA_JSON_FILENAME);

        JSONObject adb;
        JSONObject marionette;
        try {
            listener.getLogger().println("Device session started on device: " + device.getId());
            LOGGER.log(Level.INFO, "Device session started on device: " + device.getId());
            adb = getProxy("adb", client, session);
            marionette = getProxy("marionette", client, session);
        } catch (IOException ioe) {
            listener.getLogger().println("Failed to fetch proxy entries " + ioe.getMessage());
            releaseDeviceSession(listener, client, session);
            throw ioe;
        } catch (InterruptedException ie) {
            listener.getLogger().println("Failed to fetch proxy entries " + ie.getMessage());
            releaseDeviceSession(listener, client, session);
            throw ie;
        }

        listener.getLogger().println("Started device session: " + session.getId());
        listener.getLogger().println("ADB port: " + adb.getString("port"));
        listener.getLogger().println("ADB host: " + cloudHost);
        listener.getLogger().println("Android serial: " + adb.getString("serialId"));
        listener.getLogger().println("Marionette port: " + marionette.getString("port"));
        listener.getLogger().println("Marionette host: " + cloudHost);

        return new TestdroidSessionEnvironment(client, session, adb, marionette) {

            @Override
            public void buildEnvVars(Map<String, String> env) {
                env.put("SESSION_ID", Long.toString(apiDeviceSession.getId()));
                env.put("ADB_PORT", adbJSONObject.getString("port"));
                env.put("ADB_HOST", cloudHost);
                env.put("DEVICE_DATA", DEVICE_DATA_JSON_FILENAME);
                env.put("ANDROID_SERIAL", adbJSONObject.getString("serialId"));
                env.put("MARIONETTE_PORT", marionetteJSONObject.getString("port"));
                env.put("MARIONETTE_HOST", cloudHost);
            }

            @Override
            @SuppressWarnings("unchecked")
            public boolean tearDown(AbstractBuild build, BuildListener listener)
                    throws IOException, InterruptedException {

                if(apiDeviceSession == null) {
                    LOGGER.log(Level.WARNING, "Session was not initialized, skipping session release");
                    return true;
                }
                try {
                    releaseDeviceSession(listener, apiClient, apiDeviceSession);
                } catch (IOException e) {
                    //Recreate API client as tokens(auth or/and refresh tokens might be expired
                    final APIClient client = getAPIClient(getCloudURL(),getUsername(), getPassword(), Jenkins.getInstance().proxy);

                    releaseDeviceSession(listener, client, apiDeviceSession);

                }
                return true;
            }
        };
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

        URI workspaceURI = build.getWorkspace().toURI();
        FilePath deviceDataFile = new FilePath(launcher.getChannel(), workspaceURI.getPath() + "/" + jsonFileName);


        try {
            APIList<APIDeviceProperty> deviceProperties = client.get(String.format("/devices/%d/properties?limit=0", device.getId()), APIList.class);

            if (deviceProperties == null || deviceProperties.isEmpty()) {
                LOGGER.log(Level.INFO, "No device labels have been set for device: " + device.getId());
                return;
            }

            JSONObject jsonObject = new JSONObject();
            for (APIDeviceProperty property : deviceProperties.getData()) {
                JSONArray labels;
                String groupName = property.getPropertyGroupName();
                if (jsonObject.containsKey(groupName.toLowerCase())) {
                    labels = jsonObject.getJSONArray(groupName.toLowerCase());
                } else {
                    labels = new JSONArray();
                }
                labels.add(property.getName());
                jsonObject.put(groupName.toLowerCase(), labels);
            }

            deviceDataFile.write(jsonObject.toString(), "UTF-8");
            LOGGER.log(Level.INFO, "Device data: " + jsonObject.toString());

        } catch (APIException e) {
            listener.getLogger().println("Got APIException when reading device label information for device: " + device.getId());
            LOGGER.log(Level.WARNING, "APIException", e);
        }

    }

    private abstract class TestdroidSessionEnvironment extends Environment {
        protected APIClient apiClient;
        protected APIDeviceSession apiDeviceSession;
        protected JSONObject adbJSONObject;
        protected JSONObject marionetteJSONObject;

        public TestdroidSessionEnvironment(APIClient apiClient, APIDeviceSession apiDeviceSession, JSONObject adbJSONObject, JSONObject marionetteJSONObject) {
            this.apiClient = apiClient;
            this.apiDeviceSession = apiDeviceSession;
            this.adbJSONObject = adbJSONObject;
            this.marionetteJSONObject = marionetteJSONObject;
        }

    }

    /**
     * Search device by label from "Build Version" label group. If device is not available flash device with specific
     * build seach device again.

     * @param client
     * @param buildIdentifier
     * @param buildURL
     * @param memTotal
     * @param listener  @return
     * @throws APIException
     * @throws IOException
     * @throws InterruptedException
     */
    private APIDevice getDeviceByLabel(APIClient client, String buildIdentifier, String buildURL, String memTotal, BuildListener listener) throws APIException, IOException, InterruptedException {
        APIDevice device;
        int maxRetries = FLASH_RETRIES;
        while( (device = searchDeviceByLabel(client, buildIdentifier)) == null) {
            if(maxRetries-- < 0) {
                listener.getLogger().println(String.format("Flashing device failed, tried %d times but no device found", FLASH_RETRIES));
                throw new IOException("Device flashing failed");
            }
            runProject(listener, client, buildURL, memTotal);
        }
        return device;
    }
    private void releaseDeviceSession(final BuildListener listener, APIClient apiClient, APIDeviceSession apiDeviceSession) throws IOException {
        listener.getLogger().println("Releasing device session");
        try {
            apiClient.post(String.format("/me/device-sessions/%d/release", apiDeviceSession.getId()), null, null);
        } catch (APIException e) {
            listener.getLogger().println("Failed to release device session " + e.getMessage());
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
    public boolean runProject(BuildListener listener, APIClient client, String buildURL, String memTotal) throws APIException, IOException, InterruptedException {
        String memoryThrottled = Integer.parseInt(memTotal) > 0 ? " and memory throttled at " + memTotal + "MB" : "";
        listener.getLogger().println("Flashing device with " + buildURL + memoryThrottled);

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
        //Search for device by name
        APIListResource<APIDevice> devices = client.getDevices(new APIDeviceQueryBuilder().search(getDeviceName()).limit(0));

        if(devices.getTotal() <1) {
            throw new IOException("Unable find device by name: " + getDeviceName());
        }
        //find device which is online
        APIDevice device = null;
        for(APIDevice d : devices.getEntity().getData()) {
            if(d.isOnline() && !d.isLocked()) {
                device = d;
                break;
            }
        }

        if(device == null) {
            throw new IOException("Unable find device by name: " + getDeviceName());
        }
        Map<String,String> usedDevicesId = new HashMap<String, String>();
        usedDevicesId.put("usedDeviceIds[]",device.getId().toString());

        //Start test run
        client.post(String.format("/runs/%s/start", testRun.getId()),usedDevicesId, APITestRun.class);
        testRun = flashProject.getTestRun(testRun.getId());
        long waitUntil = Calendar.getInstance().getTimeInMillis() + FLASH_TIMEOUT;
        while(!testRun.getState().equals(APITestRun.State.FINISHED)) {

            try {

                Thread.sleep(10000);

                if (waitUntil < Calendar.getInstance().getTimeInMillis()) {
                    //abort run if it's still in WAITING state
                    testRun.refresh();
                    if(!testRun.getState().equals(APITestRun.State.WAITING)) {
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

    public APIDevice searchDeviceByLabel(APIClient client, String buildLabel) throws APIException {
        APIListResource<APILabelGroup> labelGroupsResource = client
                .getLabelGroups(new APIQueryBuilder().search(BUILD_IDENTIFIER_LABEL_GROUP));
        APIList<APILabelGroup> labelGroupsList = labelGroupsResource.getEntity();

        if(labelGroupsList == null || labelGroupsList.getTotal() <= 0) {
            LOGGER.log(Level.SEVERE, "Unable to find label group: " + BUILD_IDENTIFIER_LABEL_GROUP);
            return null;
        }

        APILabelGroup labelGroup = labelGroupsList.get(0);

        APIListResource<APIDeviceProperty> devicePropertiesResource = labelGroup
                .getDevicePropertiesResource(new APIQueryBuilder().search(buildLabel));
        APIList<APIDeviceProperty> devicePropertiesList = devicePropertiesResource.getEntity();
        if(devicePropertiesList == null || devicePropertiesList.getTotal() <= 0) {
            LOGGER.log(Level.SEVERE, "Unable to find label: " + buildLabel);
            return null;
        }

        APIDeviceProperty deviceProperty = devicePropertiesList.get(0);
        deviceProperty.getDisplayName();


        APIListResource<APIDevice> devicesResource = client.getDevices(new APIDeviceQueryBuilder()
                .filterWithLabelIds(deviceProperty.getId()));
        LOGGER.log(Level.INFO, String.format("\nGot %s device(s) with label %s", devicesResource.getTotal(), deviceProperty.getDisplayName()));

        //get the first device with specific label
        for (APIDevice device : devicesResource.getEntity().getData()) {
            LOGGER.log(Level.INFO, String.format("Found device %s with label %s and ID %d ", device.getDisplayName(), buildLabel, device.getId()));
            return device;
        }
        LOGGER.log(Level.INFO, String.format("Unable to find any device with label %s (label group: %s)", buildLabel, BUILD_IDENTIFIER_LABEL_GROUP));
        return null;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public String getBuildURL() {
        return buildURL;
    }

    public String getMemTotal() {
        return memTotal != null ? memTotal : "0";
    }

    public String getCloudURL() {
        return cloudURL;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
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
            save();
            return true;
        }

        @Override
        public boolean isApplicable(AbstractProject<?, ?> item) {
            return true;
        }

        public FormValidation doCheckBuildURL(@QueryParameter String value) throws IOException, ServletException {
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.error("Build URL is mandatory");
            }
            try {
                new URI(value);
                return FormValidation.ok();
            } catch (Exception e) {
                return FormValidation.warning("Unable to validate URL. " + e.getMessage());
            }
        }

        public FormValidation doCheckDeviceName(@QueryParameter String value) throws IOException, ServletException {
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.error("Device name is mandatory");
            } else {
                return FormValidation.ok();
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
                return FormValidation.warning("Unable to validate URL. " + e.getMessage());
            }
        }

        public FormValidation doCheckMemTotal(@QueryParameter String value) throws IOException, ServletException {
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
