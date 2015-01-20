package com.testdroid.jenkins.plugins.devicesessions;

import com.testdroid.api.*;
import com.testdroid.api.model.*;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.tasks.BuildWrapper;
import hudson.tasks.BuildWrapperDescriptor;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import net.sf.json.JSONSerializer;
import org.apache.commons.io.IOUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.export.ExportedBean;

import java.io.IOException;
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
    private final static String BUILD_VERSION_LABEL_GROUP = "Build version";
    //default flash project name in testdroid TODO: add it as parameter
    private final static String FLASH_PROJECT_NAME = "flash-fxos";
    //maximum waiting time for flash project to finish
    private final static int FLASH_TIMEOUT = 10*60*1000;

    private final static int FLASH_RETRIES = 3;

    private DescriptorImpl descriptor;
    //testdroid API endpoint
    private String cloudURL;
    //URL for device image
    private String flashImageURL;
    //Testdroid username
    private String username;
    //Testdroid password
    private String password;
    //Name or device model id
    private String deviceName;

    //Testdroid client
    private DefaultAPIClient client;
    //Testdroid session
    private APIDeviceSession session;
    //Testdroid ADB proxy
    private JSONObject adb;
    //Testdroid Marionette proxy
    private JSONObject marionette;

    @DataBoundConstructor
    @SuppressWarnings("hiding")
    public DeviceSessionWrapper(String cloudURL, String username, String password, String deviceName, String flashImageURL) {
        this.cloudURL = cloudURL;
        this.username = username;
        this.password = password;
        this.flashImageURL = flashImageURL;
        this.deviceName = deviceName;
    }

    /**
     * Sets up build environment
     * <p/>
     * Create device session here and inject device session Id(+other params)
     */
    @Override
    @SuppressWarnings({"hiding", "unchecked"})
    public Environment setUp(final AbstractBuild build, final Launcher launcher, final BuildListener listener) throws IOException {

        listener.getLogger().println("Connecting to " + getCloudURL() + " as " + getUsername());


        client = new DefaultAPIClient(getCloudURL(), getUsername(), getPassword());
        APIUser user = null;
        try {
            user = client.me();
            // TODO: Catch/prevent null pointer exception for invalid URL
        } catch (APIException e) {
            listener.getLogger().println("Connection failed! " + e.getMessage());
            throw new IOException(e);
        }

        final URL cloudURL = new URL(getCloudURL());

        //Look for device having "Build version" label group with label {flashImageURL}
        //if not matching device is not found run "reflash" project and look again.
        APIDevice device = null;
        try {
            int maxRetries = FLASH_RETRIES;
            while( (device = searchDeviceByLabel(client, getFlashImageURL())) == null) {
                if(maxRetries-- < 0 ) {
                    listener.getLogger().println(String.format("Flashing device failed, tried %d times but no device found",FLASH_RETRIES));
                    throw new IOException("Device flashing failed");
                }
                listener.getLogger().println("Flashing device with specific build");
                runProject(client, getFlashImageURL());

            }

        } catch (APIException e) {
            listener.getLogger().println("Failed to retrieve device by build version" + e.getMessage());
            throw new IOException(e);
        }

        listener.getLogger().println("Requesting device session for " + device.getDisplayName());

        Map<String, String> deviceSessionsParams = new HashMap<String, String>();
        deviceSessionsParams.put("deviceModelId", device.getId().toString());

        try {
            session = client.post("/me/device-sessions", deviceSessionsParams, APIDeviceSession.class);
        } catch (APIException e) {
            listener.getLogger().println("Failed to start device session" + e.getMessage());
            throw new IOException(e);
        }
        try {
            listener.getLogger().println("Device session started on device: " + device.getDisplayName());
            adb = getProxy("adb");
            marionette = getProxy("marionette");
        } catch (IOException ioe) {
            releaseDeviceSession(listener);
            throw ioe;
        }

        return new Environment() {

            @Override
            public void buildEnvVars(Map<String, String> env) {
                listener.getLogger().println("Successfully started device session with ID: " + session.getId());
                    env.put("SESSION_ID", Long.toString(session.getId()));

                // ADB environment variables
                listener.getLogger().println("ADB port: " + adb.getString("port"));
                env.put("ADB_PORT", adb.getString("port"));

                listener.getLogger().println("ADB host: " + cloudURL.getHost());
                env.put("ADB_HOST", cloudURL.getHost());


                listener.getLogger().println("Android serial: " + adb.getString("serialId"));
                env.put("ANDROID_SERIAL", adb.getString("serialId"));

                // Marionette environment variables
                listener.getLogger().println("Marionette port: " + marionette.getString("port"));
                env.put("MARIONETTE_PORT", marionette.getString("port"));

                listener.getLogger().println("Marionette host: " + cloudURL.getHost());
                env.put("MARIONETTE_HOST", cloudURL.getHost());


            }

            @Override
            @SuppressWarnings("unchecked")
            public boolean tearDown(AbstractBuild build, BuildListener listener)
                    throws IOException, InterruptedException {
                if(session == null) {
                    LOGGER.log(Level.WARNING, "Session was not initialized skipping session release");
                    return true;
                }
                releaseDeviceSession(listener);
                return true;
            }
        };
    }
    private void releaseDeviceSession(final BuildListener listener) throws IOException {
        listener.getLogger().println("Releasing device session");
        try {
            client.post(String.format("/me/device-sessions/%d/release", session.getId()), null, null);
        } catch (APIException e) {
            listener.getLogger().println("Failed to release device session" + e.getMessage());
            throw new IOException(e);
        }
    }
    /**
     * Run "flash" project and wait it has completed
     * @return
     */
    public boolean runProject(APIClient client, String buildLabel) throws APIException {
        APIUser user = client.me();
        APIListResource<APIProject>  projectAPIListResource = user.getProjectsResource(new APIQueryBuilder().search(FLASH_PROJECT_NAME));
        APIList<APIProject> projectList = projectAPIListResource.getEntity();
        if(projectList == null || projectList.getTotal() <= 0) {
            LOGGER.log(Level.SEVERE, "Unable find project:"+FLASH_PROJECT_NAME);
            return false;
        }
        APIProject flashProject = projectList.get(0);
        //remove old params
        APITestRunConfig config = flashProject.getTestRunConfig();
        APIListResource<APITestRunParameter>  params = flashProject.getTestRunConfig().getParameters();
        for(int i = params.getEntity().getTotal(); i > 0; i--) {
            APITestRunParameter param = params.getEntity().get(i);
            config.deleteParameter(param.getId());
        }
        config.createParameter("FLAME_ZIP_URL", buildLabel);
        //Search for device by name
        APIListResource<APIDevice> devices = client.getDevices(new APIDeviceQueryBuilder().search(getDeviceName()));
        List<Long> usedDevicesId = new ArrayList<Long>();
        usedDevicesId.add(devices.getEntity().get(0).getId());
        APITestRun testRun = flashProject.run(usedDevicesId);
        long waitUntil = Calendar.getInstance().getTimeInMillis()+FLASH_TIMEOUT;
        while(!testRun.getState().equals(APITestRun.State.FINISHED)) {

            try {
                Thread.sleep(10000);
            } catch (InterruptedException e) {
                //ignoring
            }
            if(waitUntil <  Calendar.getInstance().getTimeInMillis()) {
                LOGGER.log(Level.SEVERE, String.format("Flash project didn't finish in %d seconds", FLASH_TIMEOUT/1000));
                return false;
            }
        }
        return true;
    }

    public APIDevice searchDeviceByLabel(APIClient client, String buildLabel) throws APIException {
        APIListResource<APILabelGroup> labelGroupsResource = client
                .getLabelGroups(new APIQueryBuilder().search(BUILD_VERSION_LABEL_GROUP));
        APIList<APILabelGroup> labelGroupsList = labelGroupsResource.getEntity();

        if(labelGroupsList == null || labelGroupsList.getTotal() <= 0) {
            LOGGER.log(Level.SEVERE, "Unable find label group:"+BUILD_VERSION_LABEL_GROUP);
            return null;
        }

        APILabelGroup labelGroup = labelGroupsList.get(0);

        APIListResource<APIDeviceProperty> devicePropertiesResource = labelGroup
                .getDevicePropertiesResource(new APIQueryBuilder().search(buildLabel));
        APIList<APIDeviceProperty> devicePropertiesList = devicePropertiesResource.getEntity();
        if(devicePropertiesList == null || devicePropertiesList.getTotal() <= 0) {
            LOGGER.log(Level.SEVERE, "Unable find label "+buildLabel);
            return null;
        }

        APIDeviceProperty deviceProperty = devicePropertiesList.get(0);
        deviceProperty.getDisplayName();


        APIListResource<APIDevice> devicesResource = client.getDevices(new APIDeviceQueryBuilder()
                .filterWithLabelIds(deviceProperty.getId()));
        System.out.println(String.format("\nGet %s devices with label %s", devicesResource.getTotal(), deviceProperty.getDisplayName()));

        //get the first device with specific label
        for (APIDevice device : devicesResource.getEntity().getData()) {
            LOGGER.log(Level.INFO, String.format("Found device %s with label %s", device.getDisplayName(), buildLabel));
            return device;
        }
        LOGGER.log(Level.INFO, String.format("Unable to find any device with label %s (label group:%s)", buildLabel), BUILD_VERSION_LABEL_GROUP );
        return null;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public String getFlashImageURL() {
        return flashImageURL;
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

    private JSONObject getProxy(String type) throws IOException {
        int maxRetries = 3;
        try {
            String response;
            JSONArray proxyEntries;
            String queryTemplate = "{\"type\":\"%s\", \"sessionId\": %d}";
            String proxyURL = String.format("/proxy-plugin/proxies?where=%s", URLEncoder.encode(String.format(queryTemplate, type, session.getId()), "UTF-8") );
            while((response = IOUtils.toString(client.get(proxyURL))) != null) {

                LOGGER.log(Level.WARNING, "Testdroid " + type + " proxy response: " + response+"URL:"+proxyURL);

                proxyEntries = (JSONArray) JSONSerializer.toJSON(response);
                if (proxyEntries.isEmpty()) {
                    if(maxRetries-- > 0 ) {
                        try {
                            Thread.sleep(10*1000);
                        } catch (InterruptedException e) {
                            //ignoring
                        }
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
    }
}
