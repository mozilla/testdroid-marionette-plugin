package jenkins.plugins.testdroid;

import org.kohsuke.stapler.DataBoundConstructor;

public class DeviceFilter {

    public final String group;
    public final String label;

    @DataBoundConstructor
    public DeviceFilter(String group, String label) {
        this.group = group;
        this.label = label;
    }

    @Override
    public String toString() {
        return "[DeviceFilter: group=" + group + ", label=" + label + "]";
    }
}