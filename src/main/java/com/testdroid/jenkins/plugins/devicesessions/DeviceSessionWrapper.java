package com.testdroid.jenkins.plugins.devicesessions;

import com.testdroid.api.APIClient;
import com.testdroid.api.APIException;
import com.testdroid.api.DefaultAPIClient;
import com.testdroid.api.model.APIDeviceSession;
import com.testdroid.api.model.APIUser;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.tasks.BuildWrapper;
import hudson.tasks.BuildWrapperDescriptor;
import net.sf.json.JSONObject;
import net.sf.json.JSONSerializer;
import org.apache.commons.io.IOUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.export.ExportedBean;

import java.io.IOException;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

@ExportedBean
public class DeviceSessionWrapper extends BuildWrapper {

    private static final transient Logger LOGGER = Logger.getLogger(DeviceSessionWrapper.class.getName());

    private static final long serialVersionUID = 1L;

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
    private String deviceId;

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
    public DeviceSessionWrapper(String cloudURL, String username, String password, String deviceId, String flashImageURL) {
        this.cloudURL = cloudURL;
        this.username = username;
        this.password = password;
        this.flashImageURL = flashImageURL;
        this.deviceId = deviceId;
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

        listener.getLogger().println("Requesting device session for " + getDeviceId());
        // TODO: Create device session by name
        //session = user.createDeviceSession(getDeviceId());
        // TODO: Throw IOException if we do not have a session

        adb = getProxy("adb");
        marionette = getProxy("marionette");

        return new Environment() {

            @Override
            public void buildEnvVars(Map<String, String> env) {
                listener.getLogger().println("Successfully started device session with ID: " + session.getId());
                env.put("SESSION_ID", Long.toString(session.getId()));

                // ADB environment variables
                listener.getLogger().println("ADB port: " + adb.getString("port"));
                env.put("ADB_PORT", adb.getString("port"));
                listener.getLogger().println("ADB host: " + adb.getString("host"));
                env.put("ADB_HOST", adb.getString("host"));
                listener.getLogger().println("Android serial: " + adb.getString("serialId"));
                env.put("ANDROID_SERIAL", adb.getString("serialId"));

                // Marionette environment variables
                listener.getLogger().println("Marionette port: " + marionette.getString("port"));
                env.put("MARIONETTE_PORT", marionette.getString("port"));
                listener.getLogger().println("Marionette host: " + marionette.getString("host"));
                env.put("MARIONETTE_HOST", marionette.getString("host"));
            }

            @Override
            @SuppressWarnings("unchecked")
            public boolean tearDown(AbstractBuild build, BuildListener listener)
                    throws IOException, InterruptedException {
                listener.getLogger().println("Stopping device session");
                // TODO: Delete device session
                return true;
            }
        };
    }

    public String getDeviceId() {
        return deviceId;
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
        try {
            String response = IOUtils.toString(client.get("/proxy-plugin/proxies?type=" + type + "&sessionId=" + session.getId()), "UTF-8");
            LOGGER.log(Level.FINE, "Testdroid " + type + " proxy response: " + response);
            return (JSONObject) JSONSerializer.toJSON(response);
        } catch (APIException e) {
            throw new IOException(e);
        }
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
